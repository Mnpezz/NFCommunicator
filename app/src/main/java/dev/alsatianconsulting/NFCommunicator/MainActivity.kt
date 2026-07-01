/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
package dev.alsatianconsulting.NFCommunicator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
