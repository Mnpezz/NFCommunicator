/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
package dev.alsatianconsulting.NFCommunicator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.alsatianconsulting.NFCommunicator.ui.NfcCommunicatorApp
import dev.alsatianconsulting.NFCommunicator.ui.theme.NfcCommunicatorTheme
import dev.alsatianconsulting.NFCommunicator.domain.NostrEngine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val viewModel: NfcViewModel by viewModels()
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    private var readerModeEnabled = false
    private var nfcStateReceiverRegistered = false
    // @Volatile ensures both variables are coherently visible on the NFC reader thread and the main
    // thread without requiring synchronisation blocks (PL-1).
    @Volatile private var lastPassiveTagSignature: String? = null
    @Volatile private var lastPassiveTagTimestampMs: Long = 0L
    private val nfcStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    syncNfcStateFromSystem()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initContext(this)
        // FLAG_SECURE prevents the decrypted message from appearing in screenshots, screen
        // recordings, and the recent-apps thumbnail — important for a privacy-sensitive messenger.
        // Disabled only in the screenshot build type so Play Store captures can be taken.
        if (BuildConfig.SECURE_WINDOW) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(getColor(R.color.ember_primary_dark)),
            navigationBarStyle = SystemBarStyle.dark(getColor(R.color.ember_surface)),
        )
        syncNfcStateFromSystem()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.feedbackEvents.collect(::showOperationFeedback)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nostrSignerResults.collect { event ->
                    val resultIntent = Intent()
                    when (event) {
                        is NostrSignerResultEvent.Success -> {
                            resultIntent.putExtra("signature", event.result)
                            resultIntent.putExtra("result", event.result)
                            resultIntent.putExtra("package", packageName)
                            event.event?.let { resultIntent.putExtra("event", it) }
                            event.id?.let { resultIntent.putExtra("id", it) }

                            // Save approved calling package name
                            val caller = callingPackage
                            if (caller != null) {
                                val prefs = getSharedPreferences("auto_sign_prefs", android.content.Context.MODE_PRIVATE)
                                val approved = prefs.getStringSet("approved_packages", emptySet()) ?: emptySet()
                                if (!approved.contains(caller)) {
                                    val newSet = approved.toMutableSet()
                                    newSet.add(caller)
                                    prefs.edit().putStringSet("approved_packages", newSet).apply()
                                }
                            }
                        }
                        is NostrSignerResultEvent.Rejected -> {
                            resultIntent.putExtra("rejected", true)
                            event.id?.let { resultIntent.putExtra("id", it) }
                        }
                    }
                    val resultCode = when (event) {
                        is NostrSignerResultEvent.Success -> android.app.Activity.RESULT_OK
                        is NostrSignerResultEvent.Rejected -> android.app.Activity.RESULT_CANCELED
                    }
                    setResult(resultCode, resultIntent)
                    finish()
                }
            }
        }

        handleIncomingIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            NfcCommunicatorTheme {
                DisposableEffect(uiState.canScanNfc) {
                    syncReaderMode(uiState.canScanNfc)
                    onDispose {
                        disableReaderMode()
                    }
                }

                NfcCommunicatorApp(
                    uiState = uiState,
                    onScreenSelected = viewModel::setSelectedScreen,
                    onReadPasswordChanged = viewModel::updateReadPassword,
                    onWritePasswordChanged = viewModel::updateWritePassword,
                    onWritePasswordConfirmationChanged = viewModel::updateWritePasswordConfirmation,
                    onWriteMessageChanged = viewModel::updateWriteMessage,
                    onStartRead = viewModel::beginReadScan,
                    onStartWrite = viewModel::beginWriteScan,
                    onClearCard = viewModel::beginClearScan,
                    onCancelPendingScan = viewModel::cancelPendingScan,
                    onGenerateMnemonic = viewModel::generateMnemonic,
                    onUseGeneratedMnemonic = viewModel::useGeneratedMnemonic,
                    onClearGeneratedMnemonic = viewModel::clearGeneratedMnemonic,
                    onRefreshBalance = viewModel::refreshBalance,
                    onSendRecipientChanged = viewModel::updateSendRecipient,
                    onSendAmountChanged = viewModel::updateSendAmount,
                    onSendFeeRateChanged = viewModel::updateSendFeeRate,
                    onConfirmPasswordChanged = viewModel::updateConfirmPassword,
                    onInitiateSend = viewModel::initiateSend,
                    onConfirmSendAndScan = viewModel::confirmSendAndScan,
                    onCancelSend = viewModel::cancelSend,
                    onCloseWallet = viewModel::closeWallet,
                    onStartMultiNfcUnlock = viewModel::beginMultiNfcUnlock,
                    onWriteIsMultiNfcSplitChanged = viewModel::updateWriteIsMultiNfcSplit,
                    onWriteMultiNfcNChanged = viewModel::updateWriteMultiNfcN,
                    onWriteMultiNfcKChanged = viewModel::updateWriteMultiNfcK,
                    onSelectAddressType = viewModel::selectAddressType,
                    onToggleUtxoSelection = viewModel::toggleUtxoSelection,
                    onWriteIsDuressEnabledChanged = viewModel::updateWriteIsDuressEnabled,
                    onWriteEmergencyPasswordChanged = viewModel::updateWriteEmergencyPassword,
                    onWriteEmergencyPasswordConfirmationChanged = viewModel::updateWriteEmergencyPasswordConfirmation,
                    onWriteEmergencyMessageChanged = viewModel::updateWriteEmergencyMessage,
                    onGenerateEmergencyMnemonic = viewModel::generateEmergencyMnemonic,
                    onUseGeneratedEmergencyMnemonic = viewModel::useGeneratedEmergencyMnemonic,
                    onClearGeneratedEmergencyMnemonic = viewModel::clearGeneratedEmergencyMnemonic,
                    onBreezApiKeyChanged = viewModel::onBreezApiKeyChanged,
                    onBreezNetworkChanged = viewModel::onBreezNetworkChanged,
                    onBreezConnect = viewModel::onBreezConnect,
                    onBreezDisconnect = viewModel::onBreezDisconnect,
                    onFetchBreezBalance = viewModel::onFetchBreezBalance,
                    onBreezReceiveAmountChanged = viewModel::onBreezReceiveAmountChanged,
                    onBreezGenerateInvoice = viewModel::onBreezGenerateInvoice,
                    onBreezSendDestinationChanged = viewModel::onBreezSendDestinationChanged,
                    onBreezSendAmountChanged = viewModel::onBreezSendAmountChanged,
                    onBreezPrepareSend = viewModel::onBreezPrepareSend,
                    onBreezConfirmSend = viewModel::onBreezConfirmSend,
                    onBreezClearSend = viewModel::onBreezClearSend,
                    onBreezClearReceive = viewModel::onBreezClearReceive,
                    onCashuMintUrlChanged = viewModel::onCashuMintUrlChanged,
                    onCashuMintAmountInputChanged = viewModel::onCashuMintAmountInputChanged,
                    onCashuSendAmountInputChanged = viewModel::onCashuSendAmountInputChanged,
                    onCashuReceiveTokenInputChanged = viewModel::onCashuReceiveTokenInputChanged,
                    onCashuMeltInvoiceChanged = viewModel::onCashuMeltInvoiceChanged,
                    onRequestCashuMintQuote = viewModel::requestCashuMintQuote,
                    onCheckAndClaimCashuMint = viewModel::checkAndClaimCashuMint,
                    onClearCashuMintQuote = viewModel::clearCashuMintQuote,
                    onGenerateCashuSendToken = viewModel::generateCashuSendToken,
                    onClaimCashuToken = viewModel::claimCashuToken,
                    onRequestCashuMeltQuote = viewModel::requestCashuMeltQuote,
                    onConfirmAndExecuteMelt = viewModel::confirmAndExecuteMelt,
                    onClearCashuMeltQuote = viewModel::clearCashuMeltQuote,
                    onClearCashuGeneratedToken = viewModel::clearCashuGeneratedToken,
                    onClearCashuMeltSuccess = viewModel::clearCashuMeltSuccess,
                    onClearCashuError = viewModel::clearCashuError,
                    onStartQrScan = viewModel::startQrScan,
                    onCancelQrScan = viewModel::cancelQrScan,
                    onCompleteQrScan = viewModel::completeQrScan,
                    onApproveNostrRequestWithCurrent = viewModel::approveNostrSignerRequestWithCurrentWallet,
                    onRejectNostrRequest = viewModel::rejectNostrSignerRequest,
                    onAutoSignRememberChanged = {},
                    onClearAutoSignRules = viewModel::clearAutoSignRules,
                    onToggleAutoSignKind22242 = viewModel::setAutoSignKind22242,
                    onToggleAutoSignKind10050 = viewModel::setAutoSignKind10050,
                    onToggleAutoSignKind31234 = viewModel::setAutoSignKind31234,
                    onToggleAutoSignKind5 = viewModel::setAutoSignKind5,
                    onToggleAutoSignNipEncrypt = viewModel::setAutoSignNipEncrypt,
                    onToggleAutoSignNipDecrypt = viewModel::setAutoSignNipDecrypt,
                    onDismissNostrError = viewModel::dismissNostrSignerErrorAndReject
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!nfcStateReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                nfcStateReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            nfcStateReceiverRegistered = true
        }
        syncNfcStateFromSystem()
    }

    override fun onStop() {
        if (nfcStateReceiverRegistered) {
            unregisterReceiver(nfcStateReceiver)
            nfcStateReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        syncNfcStateFromSystem()
        val state = viewModel.uiState.value
        syncReaderMode(state.canScanNfc)
    }

    override fun onPause() {
        disableReaderMode()
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        val state = viewModel.uiState.value
        if (state.isProcessing) {
            return
        }

        if (state.pendingScanAction != null) {
            runOnUiThread(::showCardDetectedFeedback)
            viewModel.handleTag(tag)
            return
        }

        if (shouldSuppressPassiveDetection(tag)) {
            return
        }

        runOnUiThread(::showPassiveCardDetectedFeedback)
        if (state.selectedScreen == AppScreen.Read) {
            viewModel.handlePassiveReadTag(tag)
        } else {
            viewModel.noteDetectedTag(tag)
        }
    }

    private fun syncReaderMode(shouldEnable: Boolean) {
        val adapter = nfcAdapter ?: return
        if (readerModeEnabled == shouldEnable) {
            return
        }

        if (shouldEnable) {
            adapter.enableReaderMode(this, this, READER_FLAGS, null)
        } else {
            adapter.disableReaderMode(this)
        }
        readerModeEnabled = shouldEnable
    }

    private fun syncNfcStateFromSystem() {
        val adapter = nfcAdapter
        viewModel.setNfcState(
            isAvailable = adapter != null,
            isEnabled = adapter?.isEnabled == true,
        )
    }

    private fun disableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!readerModeEnabled) {
            return
        }
        adapter.disableReaderMode(this)
        readerModeEnabled = false
    }

    private fun showCardDetectedFeedback() {
        vibrate(VibrationEffect.createOneShot(SCAN_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showPassiveCardDetectedFeedback() {
        Toast.makeText(this, "Card detected.", Toast.LENGTH_SHORT).show()
        vibrate(VibrationEffect.createOneShot(PASSIVE_SCAN_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showOperationFeedback(event: UserFeedbackEvent) {
        Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
        when (event.tone) {
            FeedbackTone.Success -> vibrateSuccess()
            FeedbackTone.Error -> vibrateError()
        }
    }

    private fun vibrateSuccess() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 50L, 40L, 70L), -1))
    }

    private fun vibrateError() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 35L, 35L, 35L), -1))
    }

    private fun vibrate(effect: VibrationEffect) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return

        if (!vibrator.hasVibrator()) {
            return
        }

        vibrator.vibrate(effect)
    }

    private fun shouldSuppressPassiveDetection(tag: Tag): Boolean {
        val signature = passiveTagSignature(tag)
        val now = SystemClock.elapsedRealtime()
        val shouldSuppress =
            signature == lastPassiveTagSignature &&
                now - lastPassiveTagTimestampMs < PASSIVE_SCAN_DEBOUNCE_MS

        lastPassiveTagSignature = signature
        lastPassiveTagTimestampMs = now
        return shouldSuppress
    }

    private fun passiveTagSignature(tag: Tag): String {
        val idPart = tag.id?.joinToString(separator = "") { byte -> "%02x".format(byte) }.orEmpty()
        val techPart = tag.techList.joinToString(",")
        return "$idPart|$techPart"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_MAIN) {
            viewModel.clearNostrSignerRequest()
            return
        }
        val request = parseNostrSignerRequest(intent, callingPackage)
        android.util.Log.d("NfcMainActivity", "handleIncomingIntent: request type=${request?.type}, id=${request?.id}, eventJson=${request?.eventJson}")
        if (request != null) {
            viewModel.setNostrSignerRequest(request)
        }
    }

    private fun safeGetQueryParameter(uri: Uri?, key: String): String? {
        if (uri == null) return null
        if (uri.isHierarchical) {
            return uri.getQueryParameter(key)
        }
        val ssp = uri.schemeSpecificPart ?: return null
        val queryIndex = ssp.indexOf('?')
        if (queryIndex == -1) return null
        val queryString = ssp.substring(queryIndex + 1)
        return queryString.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == key }
            ?.get(1)
            ?.let { Uri.decode(it) }
    }

    private fun parseNostrSignerRequest(intent: Intent, callingPackage: String?): NostrSignerRequest? {
        return try {
            val uri = intent.data
            val type = intent.getStringExtra("type") ?: safeGetQueryParameter(uri, "type")
            if (type == null) {
                return null
            }

            val id = intent.getStringExtra("id") ?: safeGetQueryParameter(uri, "id")
            val ssp = uri?.schemeSpecificPart?.substringBefore("?")

            var eventJson: String? = intent.getStringExtra("event")
            if (eventJson == null && (type == "sign_event" || type == "decrypt_zap_event")) {
                eventJson = ssp ?: safeGetQueryParameter(uri, "event")
            }

            var plaintext: String? = null
            var ciphertext: String? = null
            if (type == "nip04_encrypt" || type == "nip44_encrypt") {
                plaintext = intent.getStringExtra("content") ?: ssp ?: safeGetQueryParameter(uri, "content")
            } else if (type == "nip04_decrypt" || type == "nip44_decrypt") {
                ciphertext = intent.getStringExtra("content") ?: ssp ?: safeGetQueryParameter(uri, "content")
            }

            val destPubkey = NostrEngine.normalizeNostrPubKey(
                intent.getStringExtra("pubKey")
                    ?: intent.getStringExtra("pubkey")
                    ?: safeGetQueryParameter(uri, "pubKey")
                    ?: safeGetQueryParameter(uri, "pubkey")
            )

            val currentUser = NostrEngine.normalizeNostrPubKey(
                intent.getStringExtra("current_user")
                    ?: intent.getStringExtra("currentUser")
                    ?: safeGetQueryParameter(uri, "current_user")
                    ?: safeGetQueryParameter(uri, "currentUser")
            )

            var resolvedCurrentUser = currentUser
            if (resolvedCurrentUser == null && eventJson != null) {
                runCatching {
                    val obj = org.json.JSONObject(eventJson)
                    val pubkey = obj.optString("pubkey", "")
                    if (pubkey.isNotEmpty()) {
                        resolvedCurrentUser = NostrEngine.normalizeNostrPubKey(pubkey)
                    }
                }.getOrNull()
            }

            val ivBytes = try { intent.getByteArrayExtra("iv") } catch (e: Exception) { null }
            val iv = if (ivBytes != null && ivBytes.isNotEmpty()) {
                android.util.Base64.encodeToString(ivBytes, android.util.Base64.NO_WRAP)
            } else {
                val ivStr = try { intent.getStringExtra("iv") } catch (e: Exception) { null }
                ivStr ?: safeGetQueryParameter(uri, "iv")
            }

            NostrSignerRequest(
                type = type,
                id = id,
                eventJson = eventJson,
                plaintext = plaintext,
                ciphertext = ciphertext,
                destPubkey = destPubkey,
                callingPackage = callingPackage,
                currentUser = resolvedCurrentUser,
                iv = iv
            )
        } catch (e: Exception) {
            android.util.Log.e("NfcMainActivity", "Error parsing NostrSignerRequest: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val SCAN_VIBRATION_MS = 75L
        const val PASSIVE_SCAN_VIBRATION_MS = 35L
        const val PASSIVE_SCAN_DEBOUNCE_MS = 1_500L
        const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE
    }
}
