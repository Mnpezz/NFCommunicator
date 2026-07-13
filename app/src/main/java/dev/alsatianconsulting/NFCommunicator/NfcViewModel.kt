/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
package dev.alsatianconsulting.NFCommunicator

import android.nfc.Tag
import android.content.Context
import java.math.BigInteger
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.alsatianconsulting.NFCommunicator.data.AddressInfo
import dev.alsatianconsulting.NFCommunicator.data.BitcoinClient
import dev.alsatianconsulting.NFCommunicator.data.Utxo
import dev.alsatianconsulting.NFCommunicator.domain.Bip39Compressor
import dev.alsatianconsulting.NFCommunicator.domain.NfcOperationResult
import dev.alsatianconsulting.NFCommunicator.domain.NfcPayloadReadResult
import dev.alsatianconsulting.NFCommunicator.domain.NfcTagService
import dev.alsatianconsulting.NFCommunicator.domain.NfcDiagnostics
import dev.alsatianconsulting.NFCommunicator.domain.InvalidPasswordException
import dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec
import dev.alsatianconsulting.NFCommunicator.domain.StorageBackend
import dev.alsatianconsulting.NFCommunicator.domain.TagInfo
import dev.alsatianconsulting.NFCommunicator.domain.WalletEngine
import dev.alsatianconsulting.NFCommunicator.domain.ShamirSecretSharing
import dev.alsatianconsulting.NFCommunicator.domain.BreezManager
import dev.alsatianconsulting.NFCommunicator.domain.NostrEngine
import dev.alsatianconsulting.NFCommunicator.domain.NostrKeyPair
import dev.alsatianconsulting.NFCommunicator.domain.CashuProof
import dev.alsatianconsulting.NFCommunicator.domain.BlindedMessage
import dev.alsatianconsulting.NFCommunicator.domain.BlindedSignature
import dev.alsatianconsulting.NFCommunicator.domain.Secp256k1Math
import dev.alsatianconsulting.NFCommunicator.domain.CashuEngine
import dev.alsatianconsulting.NFCommunicator.domain.KeyParser
import dev.alsatianconsulting.NFCommunicator.data.CashuClient
import dev.alsatianconsulting.NFCommunicator.data.MintKeyset
import dev.alsatianconsulting.NFCommunicator.data.MintQuoteResponse
import dev.alsatianconsulting.NFCommunicator.data.MeltQuoteResponse
import dev.alsatianconsulting.NFCommunicator.data.MeltResponse
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import breez_sdk_liquid.*

enum class AppScreen {
    Read,
    Write,
}

/** Minimum number of characters required for a password (NIST SP 800-63B §5.1.1). */
const val MIN_PASSWORD_LENGTH = 8

data class StatusMessage(
    val text: String,
    val isError: Boolean = false,
    val detail: String? = null,
)

enum class FeedbackTone {
    Success,
    Error,
}

data class UserFeedbackEvent(
    val message: String,
    val tone: FeedbackTone,
)

data class NostrSignerRequest(
    val type: String,
    val id: String?,
    val eventJson: String?,
    val plaintext: String?,
    val ciphertext: String?,
    val destPubkey: String?,
    val callingPackage: String?,
    val currentUser: String? = null,
    val iv: String? = null
)

sealed interface NostrSignerResultEvent {
    data class Success(
        val type: String,
        val id: String?,
        val result: String,
        val event: String? = null
    ) : NostrSignerResultEvent

    data class Rejected(val id: String?) : NostrSignerResultEvent
}

sealed interface PendingScanAction {
    data class Read(val password: String) : PendingScanAction
    data class Write(
        val password: String,
        val message: String,
        val isDuress: Boolean = false,
        val emergencyPassword: String = "",
        val emergencyMessage: String = ""
    ) : PendingScanAction
    data class Clear(val origin: AppScreen) : PendingScanAction
    data class Sign(
        val password: String,
        val recipient: String,
        val amount: Long,
        val feeRate: Long
    ) : PendingScanAction
    data class WriteShare(
        val password: String,
        val shares: List<ByteArray>,
        val currentIndex: Int,
        val thresholdK: Int
    ) : PendingScanAction
    data class ReadShare(
        val password: String,
        val gathered: List<ByteArray>,
        val thresholdK: Int? = null
    ) : PendingScanAction
}

enum class QrTargetField {
    BtcRecipient,
    BreezDestination,
    CashuMeltInvoice,
    CashuReceiveToken
}

data class MainUiState(
    val nfcAvailable: Boolean = true,
    val nfcEnabled: Boolean = true,
    val selectedScreen: AppScreen = AppScreen.Read,
    val showQrScanner: Boolean = false,
    val qrTargetField: QrTargetField? = null,
    val pendingScanAction: PendingScanAction? = null,
    val isProcessing: Boolean = false,
    val readPassword: String = "",
    val readMessage: String? = null,
    val readStatus: StatusMessage = StatusMessage("Hold a compatible NFC tag against the phone. If a message is found, enter the password to decrypt it."),
    val writePassword: String = "",
    val writePasswordConfirmation: String = "",
    val writeMessage: String = "",
    val writeStatus: StatusMessage = StatusMessage("Enter a password and message, then tap Write to Card."),
    val estimatedNdefWriteSizeBytes: Int = 0,
    val estimatedMifareClassicWriteSizeBytes: Int = 0,
    val lastTagInfo: TagInfo? = null,
    val derivedAddresses: Map<String, String>? = null,
    val derivedAddressesList: Map<String, List<String>>? = null,
    val activeAddressIndex: Int = 0,
    val generatedMnemonic: String? = null,
    val writeIsMultiNfcSplit: Boolean = false,
    val writeMultiNfcN: Int = 3,
    val writeMultiNfcK: Int = 2,
    val isMultiNfcUnlock: Boolean = false,
    val writeIsDuressEnabled: Boolean = false,
    val writeEmergencyPassword: String = "",
    val writeEmergencyPasswordConfirmation: String = "",
    val writeEmergencyMessage: String = "",
    val generatedEmergencyMnemonic: String? = null,
    
    // Wallet Engine State
    val walletBalance: Long? = null,
    val walletUtxos: List<Utxo>? = null,
    val sendRecipient: String = "",
    val sendAmount: String = "",
    val sendFeeRate: String = "2",
    val isBroadcasting: Boolean = false,
    val broadcastTxId: String? = null,
    val broadcastError: String? = null,
    val showConfirmBottomSheet: Boolean = false,
    val confirmPassword: String = "",
    val isFetchingBalance: Boolean = false,
    val activeAddressType: String = "Native SegWit (BIP-84)",
    val selectedUtxoIds: Set<String> = emptySet(),

    // Breez Liquid SDK State
    val breezApiKey: String = "",
    val breezNetwork: String = "TESTNET", // "TESTNET" or "MAINNET"
    val breezConnected: Boolean = false,
    val breezConnecting: Boolean = false,
    val breezBalanceSat: Long? = null,
    val breezPendingSendSat: Long? = null,
    val breezPendingReceiveSat: Long? = null,
    val breezError: String? = null,
    val breezInvoiceToReceive: String? = null,
    val breezReceiveAmount: String = "",
    val breezSendDestination: String = "",
    val breezSendAmount: String = "",
    val breezPrepareResponse: PrepareSendResponse? = null,
    val breezSendEstimatedFeeSat: Long? = null,
    val breezSending: Boolean = false,
    val breezSendSuccessTxId: String? = null,
    val breezPrepareReceiveResponse: PrepareReceiveResponse? = null,
    val breezPrepareReceiveFeesSat: Long? = null,

    // Nostr Wallet State
    val nostrNsec: String? = null,
    val nostrNpub: String? = null,
    val nostrPubkeyHex: String? = null,

    // Cashu Wallet State
    val cashuBalanceSat: Long = 0L,
    val cashuMintUrl: String = "https://mint.minibits.cash/Bitcoin",
    val cashuProofs: List<CashuProof> = emptyList(),
    val cashuCounter: Long = 0L,
    val cashuMintQuote: MintQuoteResponse? = null,
    val cashuMintQuoteAmountSat: Long = 0L,
    val cashuError: String? = null,
    val cashuLoading: Boolean = false,
    val cashuMeltQuote: MeltQuoteResponse? = null,
    val cashuMeltInvoice: String = "",
    val cashuMeltSuccessPreimage: String? = null,
    val cashuMintAmountInput: String = "",
    val cashuSendAmountInput: String = "",
    val cashuReceiveTokenInput: String = "",
    val cashuGeneratedToken: String? = null,
    val nostrSignerRequest: NostrSignerRequest? = null,
    val autoSignRules: Set<String> = emptySet(),
    val autoSignKind22242: Boolean = false,
    val autoSignKind10050: Boolean = false,
    val autoSignKind31234: Boolean = false,
    val autoSignKind5: Boolean = false,
    val autoSignNipEncrypt: Boolean = false,
    val autoSignNipDecrypt: Boolean = false,
    val showSwitchAccount: Boolean = false,
    val nostrSignerError: String? = null
) {
    val canScanNfc: Boolean
        get() = nfcAvailable && nfcEnabled

    val pendingPrompt: String?
        get() = when {
            isProcessing -> "Processing the scanned tag..."
            pendingScanAction is PendingScanAction.Read ->
                "Hold a compatible NFC tag against the phone to decrypt its message."

            pendingScanAction is PendingScanAction.Write ->
                "Hold a compatible NFC tag against the phone to write the encrypted message."

            pendingScanAction is PendingScanAction.Clear ->
                "Hold a compatible NFC tag against the phone to clear this app's stored content."

            pendingScanAction is PendingScanAction.Sign ->
                "Hold your NFC tag against the phone to sign and broadcast the transaction."

            pendingScanAction is PendingScanAction.WriteShare ->
                "Hold NFC card ${pendingScanAction.currentIndex + 1} of ${pendingScanAction.shares.size} to write SSS share."

            pendingScanAction is PendingScanAction.ReadShare -> {
                val current = pendingScanAction.gathered.size + 1
                val total = pendingScanAction.thresholdK
                if (total != null) {
                    "Hold NFC card $current of $total to read SSS share."
                } else {
                    "Hold any SSS card against the phone to read SSS share."
                }
            }

            else -> null
        }
}

class NfcViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val tagService = NfcTagService()
    private val _uiState = MutableStateFlow(restoreUiState())
    private val _feedbackEvents = MutableSharedFlow<UserFeedbackEvent>(extraBufferCapacity = 4)
    private val _nostrSignerResults = Channel<NostrSignerResultEvent>(Channel.BUFFERED)
    private var cachedReadPayload: ByteArray? = null
    private var readPasswordAttemptJob: Job? = null
    private var appContext: Context? = null

    fun initContext(context: Context) {
        if (appContext == null) {
            val app = context.applicationContext
            appContext = app
            val prefs = app.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            val rules = prefs.getStringSet("rules", emptySet()) ?: emptySet()
            val kind22242 = prefs.getBoolean("kind_22242", false)
            val kind10050 = prefs.getBoolean("kind_10050", false)
            val kind31234 = prefs.getBoolean("kind_31234", false)
            val kind5 = prefs.getBoolean("kind_5", false)
            val nipEncrypt = prefs.getBoolean("nip_encrypt", false)
            val nipDecrypt = prefs.getBoolean("nip_decrypt", false)
            _uiState.update { it.copy(
                autoSignRules = rules,
                autoSignKind22242 = kind22242,
                autoSignKind10050 = kind10050,
                autoSignKind31234 = kind31234,
                autoSignKind5 = kind5,
                autoSignNipEncrypt = nipEncrypt,
                autoSignNipDecrypt = nipDecrypt
            ) }
        }
    }

    fun setAutoSignKind22242(enabled: Boolean) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("kind_22242", enabled).apply()
        }
        _uiState.update { it.copy(autoSignKind22242 = enabled) }
    }

    fun setAutoSignKind10050(enabled: Boolean) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("kind_10050", enabled).apply()
        }
        _uiState.update { it.copy(autoSignKind10050 = enabled) }
    }

    fun setAutoSignKind31234(enabled: Boolean) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("kind_31234", enabled).apply()
        }
        _uiState.update { it.copy(autoSignKind31234 = enabled) }
    }

    fun setAutoSignKind5(enabled: Boolean) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("kind_5", enabled).apply()
        }
        _uiState.update { it.copy(autoSignKind5 = enabled) }
    }

    fun setAutoSignNipEncrypt(enabled: Boolean) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("nip_encrypt", enabled).apply()
        }
        _uiState.update { it.copy(autoSignNipEncrypt = enabled) }
    }

    fun setAutoSignNipDecrypt(enabled: Boolean) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("nip_decrypt", enabled).apply()
        }
        _uiState.update { it.copy(autoSignNipDecrypt = enabled) }
    }

    fun clearAutoSignRules() {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("auto_sign_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .remove("rules")
                .putBoolean("kind_22242", false)
                .putBoolean("kind_10050", false)
                .putBoolean("kind_31234", false)
                .putBoolean("kind_5", false)
                .putBoolean("nip_encrypt", false)
                .putBoolean("nip_decrypt", false)
                .apply()
        }
        _uiState.update { it.copy(
            autoSignRules = emptySet(),
            autoSignKind22242 = false,
            autoSignKind10050 = false,
            autoSignKind31234 = false,
            autoSignKind5 = false,
            autoSignNipEncrypt = false,
            autoSignNipDecrypt = false
        ) }
    }

    private fun getAutoSignRuleKey(callingPackage: String, requestType: String, eventKind: Int?): String {
        return "$callingPackage:$requestType:${eventKind ?: ""}"
    }

    private fun getRequestEventKind(request: NostrSignerRequest): Int? {
        if (request.type != "sign_event" || request.eventJson == null) return null
        return try {
            org.json.JSONObject(request.eventJson).optInt("kind", -1).takeIf { it != -1 }
        } catch (e: Exception) {
            null
        }
    }

    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val feedbackEvents = _feedbackEvents.asSharedFlow()
    val nostrSignerResults = _nostrSignerResults.receiveAsFlow()

    init {
        viewModelScope.launch {
            uiState.collect(::persistRestorableState)
        }
        val restoredMessage = getInMemoryReadMessage()
        if (restoredMessage != null) {
            val activeType = _uiState.value.activeAddressType
            val addresses = _uiState.value.derivedAddressesList?.get(activeType) ?: emptyList()
            if (addresses.isNotEmpty()) {
                fetchBitcoinBalanceAndUtxos(addresses)
            } else {
                _uiState.value.derivedAddresses?.get(activeType)?.let { addr ->
                    fetchBitcoinBalanceAndUtxos(addr)
                }
            }
            syncEcashWallet()
        }
    }

    fun setNfcState(isAvailable: Boolean, isEnabled: Boolean) {
        _uiState.update { state ->
            when {
                !isAvailable -> {
                    state.copy(
                        nfcAvailable = false,
                        nfcEnabled = false,
                        pendingScanAction = null,
                        isProcessing = false,
                        readStatus = StatusMessage("This device does not expose an NFC adapter.", true),
                        writeStatus = StatusMessage("This device does not expose an NFC adapter.", true),
                    )
                }

                !isEnabled -> {
                    state.copy(
                        nfcAvailable = true,
                        nfcEnabled = false,
                        pendingScanAction = null,
                        isProcessing = false,
                        readStatus = StatusMessage("NFC is turned off. Turn it on in Android settings to scan cards.", true),
                        writeStatus = StatusMessage("NFC is turned off. Turn it on in Android settings to scan cards.", true),
                    )
                }

                !state.nfcAvailable || !state.nfcEnabled -> {
                    state.copy(
                        nfcAvailable = true,
                        nfcEnabled = true,
                        readStatus = defaultReadStatus(),
                        writeStatus =
                            if (state.writeMessage.isBlank()) {
                                defaultWriteStatus()
                            } else {
                                StatusMessage("Draft restored. Re-enter the password, then tap Write to Card.")
                            },
                    )
                }

                else -> {
                    state.copy(
                        nfcAvailable = true,
                        nfcEnabled = true,
                    )
                }
            }
        }
    }

    fun setNfcAvailability(isAvailable: Boolean) {
        setNfcState(isAvailable = isAvailable, isEnabled = isAvailable)
    }

    fun setSelectedScreen(screen: AppScreen) {
        _uiState.update { it.copy(selectedScreen = screen) }
    }

    fun updateReadPassword(value: String) {
        _uiState.update { state ->
            state.copy(
                readPassword = value,
                readMessage = null,
                derivedAddresses = null,
                nostrNsec = null,
                nostrNpub = null,
                nostrPubkeyHex = null,
                readStatus =
                    if (!state.canScanNfc) {
                        disabledNfcStatus(state.nfcAvailable)
                    } else if (cachedReadPayload != null && value.isBlank()) {
                        StatusMessage("Encrypted message found. Enter the password, then tap Try Password to decrypt it.")
                    } else if (cachedReadPayload != null) {
                        StatusMessage("Encrypted message found. Tap Try Password to decrypt it.")
                    } else if (state.readStatus.isError || state.readMessage != null) {
                        defaultReadStatus()
                    } else {
                        state.readStatus
                    },
            )
        }
    }

    fun updateWritePassword(value: String) {
        _uiState.update { it.copy(writePassword = value) }
    }

    fun updateWritePasswordConfirmation(value: String) {
        _uiState.update { it.copy(writePasswordConfirmation = value) }
    }

    fun updateWriteMessage(value: String) {
        _uiState.update {
            it.copy(
                writeMessage = value,
                estimatedNdefWriteSizeBytes = estimateNdefWriteSize(value),
                estimatedMifareClassicWriteSizeBytes = estimateMifareClassicWriteSize(value),
                writeStatus = if (it.writeStatus.text.startsWith("Successfully") || it.writeStatus.text.startsWith("Write")) defaultWriteStatus() else it.writeStatus
            )
        }
    }

    fun updateWriteIsDuressEnabled(value: Boolean) {
        _uiState.update { it.copy(writeIsDuressEnabled = value) }
    }

    fun updateWriteEmergencyPassword(value: String) {
        _uiState.update { it.copy(writeEmergencyPassword = value) }
    }

    fun updateWriteEmergencyPasswordConfirmation(value: String) {
        _uiState.update { it.copy(writeEmergencyPasswordConfirmation = value) }
    }

    fun updateWriteEmergencyMessage(value: String) {
        _uiState.update { it.copy(writeEmergencyMessage = value) }
    }

    fun generateEmergencyMnemonic() {
        val mnemonic = Bip39Compressor.generateMnemonic(12).joinToString(" ")
        _uiState.update { it.copy(generatedEmergencyMnemonic = mnemonic) }
    }

    fun useGeneratedEmergencyMnemonic() {
        val mnemonic = _uiState.value.generatedEmergencyMnemonic ?: return
        _uiState.update {
            it.copy(
                writeEmergencyMessage = mnemonic,
                generatedEmergencyMnemonic = null,
            )
        }
    }

    fun clearGeneratedEmergencyMnemonic() {
        _uiState.update { it.copy(generatedEmergencyMnemonic = null) }
    }

    fun beginReadScan() {
        val state = _uiState.value
        when {
            !state.nfcAvailable -> {
                updateReadStatus("This device does not expose an NFC adapter.", isError = true)
            }

            !state.nfcEnabled -> {
                updateReadStatus("NFC is turned off. Turn it on in Android settings to scan cards.", isError = true)
            }

            state.isProcessing -> Unit
            state.readPassword.isBlank() -> {
                updateReadStatus("Enter the shared password to try decrypting the detected tag.", isError = true)
            }

            state.readPassword.length < MIN_PASSWORD_LENGTH -> {
                updateReadStatus(
                    "The password must be at least $MIN_PASSWORD_LENGTH characters.",
                    isError = true,
                )
            }

            cachedReadPayload == null -> {
                updateReadStatus("Scan a compatible tag first. Cards are detected automatically on this screen.", isError = true)
            }

            else -> {
                attemptCachedReadDecryption(state.readPassword)
            }
        }
    }

    fun beginWriteScan() {
        val state = _uiState.value
        when {
            !state.nfcAvailable -> {
                updateWriteStatus("This device does not expose an NFC adapter.", isError = true)
            }

            !state.nfcEnabled -> {
                updateWriteStatus("NFC is turned off. Turn it on in Android settings to scan cards.", isError = true)
            }

            state.isProcessing -> Unit
            state.writePassword.isBlank() -> {
                updateWriteStatus("Enter a password before writing a tag.", isError = true)
            }

            state.writePassword.length < MIN_PASSWORD_LENGTH -> {
                updateWriteStatus(
                    "The password must be at least $MIN_PASSWORD_LENGTH characters.",
                    isError = true,
                )
            }

            state.writePassword != state.writePasswordConfirmation -> {
                updateWriteStatus("The password confirmation does not match.", isError = true)
            }

            state.writeMessage.isBlank() -> {
                updateWriteStatus("Enter a plain-text message before writing a tag.", isError = true)
            }

            state.writeIsDuressEnabled && state.writeEmergencyPassword.isBlank() -> {
                updateWriteStatus("Enter an emergency password before writing a tag.", isError = true)
            }

            state.writeIsDuressEnabled && state.writeEmergencyPassword.length < MIN_PASSWORD_LENGTH -> {
                updateWriteStatus(
                    "The emergency password must be at least $MIN_PASSWORD_LENGTH characters.",
                    isError = true,
                )
            }

            state.writeIsDuressEnabled && state.writeEmergencyPassword != state.writeEmergencyPasswordConfirmation -> {
                updateWriteStatus("The emergency password confirmation does not match.", isError = true)
            }

            state.writeIsDuressEnabled && state.writePassword == state.writeEmergencyPassword -> {
                updateWriteStatus("The main password and emergency password must be different.", isError = true)
            }

            state.writeIsDuressEnabled && state.writeEmergencyMessage.isBlank() -> {
                updateWriteStatus("Enter an emergency seed phrase before writing a tag.", isError = true)
            }

            else -> {
                _uiState.update {
                    if (state.writeIsMultiNfcSplit) {
                        try {
                            val messageBytes = state.writeMessage.toByteArray(Charsets.UTF_8)
                            val shares = ShamirSecretSharing.split(messageBytes, state.writeMultiNfcN, state.writeMultiNfcK)
                            it.copy(
                                pendingScanAction = PendingScanAction.WriteShare(state.writePassword, shares, 0, state.writeMultiNfcK),
                                writeStatus = StatusMessage("Ready to scan card 1 of ${state.writeMultiNfcN} for writing."),
                            )
                        } catch (e: Exception) {
                            it.copy(writeStatus = StatusMessage("Error generating secret shares: ${e.message}", isError = true))
                        }
                    } else {
                        it.copy(
                            pendingScanAction = PendingScanAction.Write(
                                password = state.writePassword,
                                message = state.writeMessage,
                                isDuress = state.writeIsDuressEnabled,
                                emergencyPassword = state.writeEmergencyPassword,
                                emergencyMessage = state.writeEmergencyMessage
                            ),
                            writeStatus = StatusMessage("Ready to scan a tag for writing."),
                        )
                    }
                }
            }
        }
    }

    fun updateWriteIsMultiNfcSplit(enabled: Boolean) {
        _uiState.update { it.copy(writeIsMultiNfcSplit = enabled) }
    }

    fun updateWriteMultiNfcN(n: Int) {
        _uiState.update { state ->
            val newN = n.coerceIn(2, 5)
            val newK = state.writeMultiNfcK.coerceIn(2, newN)
            state.copy(writeMultiNfcN = newN, writeMultiNfcK = newK)
        }
    }

    fun updateWriteMultiNfcK(k: Int) {
        _uiState.update { state ->
            val newK = k.coerceIn(2, state.writeMultiNfcN)
            state.copy(writeMultiNfcK = newK)
        }
    }

    fun onBreezApiKeyChanged(key: String) {
        _uiState.update { it.copy(breezApiKey = key) }
    }

    fun onBreezNetworkChanged(networkString: String) {
        _uiState.update { it.copy(breezNetwork = networkString) }
    }

    fun onBreezReceiveAmountChanged(amount: String) {
        _uiState.update { it.copy(breezReceiveAmount = amount) }
    }

    fun onBreezSendDestinationChanged(destination: String) {
        _uiState.update { it.copy(breezSendDestination = destination) }
    }

    fun onBreezSendAmountChanged(amount: String) {
        _uiState.update { it.copy(breezSendAmount = amount) }
    }

    fun onBreezConnect(workingDir: java.io.File) {
        val mnemonic = _uiState.value.readMessage ?: return
        val apiKey = _uiState.value.breezApiKey
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(breezError = "API key cannot be empty.") }
            return
        }
        val network = if (_uiState.value.breezNetwork == "MAINNET") LiquidNetwork.MAINNET else LiquidNetwork.TESTNET

        _uiState.update { it.copy(breezConnecting = true, breezError = null) }
        viewModelScope.launch {
            try {
                BreezManager.connect(
                    mnemonic = mnemonic,
                    apiKey = apiKey,
                    network = network,
                    workingDir = workingDir
                )
                _uiState.update { it.copy(breezConnected = true) }
                onFetchBreezBalance()
                _feedbackEvents.emit(UserFeedbackEvent("Wallet connected successfully", FeedbackTone.Success))
            } catch (e: Exception) {
                _uiState.update { it.copy(breezError = e.message ?: "Failed to connect to Breez SDK") }
                _feedbackEvents.emit(UserFeedbackEvent("Breez Connection Failed: ${e.message}", FeedbackTone.Error))
            } finally {
                _uiState.update { it.copy(breezConnecting = false) }
            }
        }
    }

    fun onBreezDisconnect() {
        _uiState.update {
            it.copy(
                breezConnected = false,
                breezBalanceSat = null,
                breezPendingSendSat = null,
                breezPendingReceiveSat = null,
                breezError = null,
                breezInvoiceToReceive = null,
                breezPrepareResponse = null,
                breezSendEstimatedFeeSat = null,
                breezSendSuccessTxId = null,
                breezPrepareReceiveResponse = null,
                breezPrepareReceiveFeesSat = null,
                breezReceiveAmount = "",
                breezSendDestination = "",
                breezSendAmount = ""
            )
        }
        viewModelScope.launch {
            try {
                BreezManager.disconnect()
            } catch (e: Throwable) {
                // Ignore
            }
            _feedbackEvents.emit(UserFeedbackEvent("Wallet disconnected", FeedbackTone.Success))
        }
    }

    fun onFetchBreezBalance() {
        if (!_uiState.value.breezConnected) return
        _uiState.update { it.copy(breezError = null) }
        viewModelScope.launch {
            try {
                val info = BreezManager.getWalletInfo()
                if (info != null) {
                    _uiState.update {
                        it.copy(
                            breezBalanceSat = info.balanceSat.toLong(),
                            breezPendingSendSat = info.pendingSendSat.toLong(),
                            breezPendingReceiveSat = info.pendingReceiveSat.toLong()
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(breezError = "Failed to fetch balance: ${e.message}") }
            }
        }
    }

    fun onBreezGenerateInvoice() {
        val amountStr = _uiState.value.breezReceiveAmount
        val amountSats = amountStr.toLongOrNull() ?: 0L
        if (amountSats <= 0) {
            _uiState.update { it.copy(breezError = "Please enter a valid amount greater than 0.") }
            return
        }
        _uiState.update { it.copy(isProcessing = true, breezError = null, breezInvoiceToReceive = null) }
        viewModelScope.launch {
            try {
                val prep = BreezManager.prepareReceive(amountSats)
                val resp = BreezManager.executeReceive(prep)
                _uiState.update {
                    it.copy(
                        breezInvoiceToReceive = resp.destination,
                        breezPrepareReceiveResponse = prep,
                        breezPrepareReceiveFeesSat = prep.feesSat.toLong()
                    )
                }
                _feedbackEvents.emit(UserFeedbackEvent("Invoice generated successfully", FeedbackTone.Success))
            } catch (e: Exception) {
                _uiState.update { it.copy(breezError = "Invoice generation failed: ${e.message}") }
                _feedbackEvents.emit(UserFeedbackEvent("Invoice generation failed", FeedbackTone.Error))
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun onBreezPrepareSend() {
        val destination = _uiState.value.breezSendDestination
        if (destination.isBlank()) {
            _uiState.update { it.copy(breezError = "Destination address/invoice cannot be empty.") }
            return
        }
        val amountStr = _uiState.value.breezSendAmount
        val amountSats = amountStr.toLongOrNull() // Optional if invoice contains amount

        _uiState.update { it.copy(isProcessing = true, breezError = null, breezSendEstimatedFeeSat = null, breezPrepareResponse = null) }
        viewModelScope.launch {
            try {
                val prep = BreezManager.prepareSend(destination, amountSats)
                _uiState.update {
                    it.copy(
                        breezPrepareResponse = prep,
                        breezSendEstimatedFeeSat = prep.feesSat?.toLong()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(breezError = "Prepare send failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun onBreezConfirmSend() {
        val prep = _uiState.value.breezPrepareResponse ?: return
        _uiState.update { it.copy(breezSending = true, breezError = null) }
        viewModelScope.launch {
            try {
                val resp = BreezManager.executeSend(prep)
                _uiState.update {
                    it.copy(
                        breezSendSuccessTxId = resp.payment.txId,
                        breezPrepareResponse = null,
                        breezSendEstimatedFeeSat = null,
                        breezSendDestination = "",
                        breezSendAmount = ""
                    )
                }
                onFetchBreezBalance()
                _feedbackEvents.emit(UserFeedbackEvent("Payment sent successfully", FeedbackTone.Success))
            } catch (e: Exception) {
                _uiState.update { it.copy(breezError = "Payment failed: ${e.message}") }
                _feedbackEvents.emit(UserFeedbackEvent("Payment failed", FeedbackTone.Error))
            } finally {
                _uiState.update { it.copy(breezSending = false) }
            }
        }
    }

    fun onBreezClearSend() {
        _uiState.update {
            it.copy(
                breezSendDestination = "",
                breezSendAmount = "",
                breezPrepareResponse = null,
                breezSendEstimatedFeeSat = null,
                breezSendSuccessTxId = null,
                breezError = null
            )
        }
    }

    fun onBreezClearReceive() {
        _uiState.update {
            it.copy(
                breezReceiveAmount = "",
                breezInvoiceToReceive = null,
                breezPrepareReceiveResponse = null,
                breezPrepareReceiveFeesSat = null,
                breezError = null
            )
        }
    }

    fun beginMultiNfcUnlock() {
        val state = _uiState.value
        when {
            !state.nfcAvailable -> {
                updateReadStatus("This device does not expose an NFC adapter.", isError = true)
            }

            !state.nfcEnabled -> {
                updateReadStatus("NFC is turned off. Turn it on in Android settings.", isError = true)
            }

            state.isProcessing -> Unit
            state.readPassword.isBlank() -> {
                updateReadStatus("Enter the password for decryption.", isError = true)
            }

            state.readPassword.length < MIN_PASSWORD_LENGTH -> {
                updateReadStatus(
                    "The password must be at least $MIN_PASSWORD_LENGTH characters.",
                    isError = true,
                )
            }

            else -> {
                _uiState.update {
                    it.copy(
                        pendingScanAction = PendingScanAction.ReadShare(state.readPassword, emptyList(), null),
                        isMultiNfcUnlock = true,
                        readStatus = StatusMessage("Ready to scan any SSS card."),
                    )
                }
            }
        }
    }

    fun beginClearScan() {
        val state = _uiState.value
        if (!state.nfcAvailable) {
            updateStatusForScreen(state.selectedScreen, "This device does not expose an NFC adapter.", true)
            return
        }
        if (!state.nfcEnabled) {
            updateStatusForScreen(state.selectedScreen, "NFC is turned off. Turn it on in Android settings to scan cards.", true)
            return
        }
        if (state.isProcessing) {
            return
        }

        _uiState.update {
            it.copy(
                pendingScanAction = PendingScanAction.Clear(state.selectedScreen),
            )
        }
        updateStatusForScreen(state.selectedScreen, "Ready to scan a tag to clear it.", false)
    }

    fun cancelPendingScan() {
        val state = _uiState.value
        if (state.isProcessing) {
            return
        }

        _uiState.update { it.copy(pendingScanAction = null) }
        updateStatusForScreen(state.selectedScreen, "Scan cancelled.", false)
     }

    fun startQrScan(target: QrTargetField) {
        _uiState.update { it.copy(showQrScanner = true, qrTargetField = target) }
    }

    fun cancelQrScan() {
        _uiState.update { it.copy(showQrScanner = false, qrTargetField = null) }
    }

    fun completeQrScan(result: String) {
        _uiState.update { state ->
            val updated = when (state.qrTargetField) {
                QrTargetField.BtcRecipient -> state.copy(sendRecipient = result)
                QrTargetField.BreezDestination -> state.copy(breezSendDestination = result)
                QrTargetField.CashuMeltInvoice -> state.copy(cashuMeltInvoice = result)
                QrTargetField.CashuReceiveToken -> state.copy(cashuReceiveTokenInput = result)
                null -> state
            }
            updated.copy(showQrScanner = false, qrTargetField = null)
        }
    }

    fun updateSendRecipient(value: String) {
        _uiState.update { it.copy(sendRecipient = value) }
    }

    fun updateSendAmount(value: String) {
        _uiState.update { it.copy(sendAmount = value) }
    }

    fun updateSendFeeRate(value: String) {
        _uiState.update { it.copy(sendFeeRate = value) }
    }

    fun updateConfirmPassword(value: String) {
        _uiState.update { it.copy(confirmPassword = value) }
    }

    fun setShowConfirmBottomSheet(show: Boolean) {
        _uiState.update { it.copy(showConfirmBottomSheet = show) }
    }

    fun closeWallet() {
        onBreezDisconnect()
        _uiState.update {
            it.copy(
                derivedAddresses = null,
                readMessage = null,
                walletBalance = null,
                walletUtxos = null,
                sendRecipient = "",
                sendAmount = "",
                confirmPassword = "",
                broadcastTxId = null,
                broadcastError = null,
                nostrNsec = null,
                nostrNpub = null,
                nostrPubkeyHex = null,
                showConfirmBottomSheet = false,
                cashuProofs = emptyList(),
                cashuBalanceSat = 0L,
                cashuMintQuote = null,
                cashuMintQuoteAmountSat = 0L,
                cashuError = null,
                cashuMeltQuote = null,
                cashuMeltInvoice = "",
                cashuMeltSuccessPreimage = null,
                cashuGeneratedToken = null
            )
        }
    }

    fun refreshBalance() {
        val addressType = _uiState.value.activeAddressType
        val addresses = _uiState.value.derivedAddressesList?.get(addressType)
        if (addresses != null) {
            fetchBitcoinBalanceAndUtxos(addresses)
        } else {
            val address = _uiState.value.derivedAddresses?.get(addressType) ?: return
            fetchBitcoinBalanceAndUtxos(address)
        }
    }

    fun selectAddressType(addressType: String) {
        _uiState.update { it.copy(activeAddressType = addressType, selectedUtxoIds = emptySet()) }
        val addresses = _uiState.value.derivedAddressesList?.get(addressType)
        if (addresses != null) {
            fetchBitcoinBalanceAndUtxos(addresses)
        } else {
            val address = _uiState.value.derivedAddresses?.get(addressType) ?: return
            fetchBitcoinBalanceAndUtxos(address)
        }
    }

    fun toggleUtxoSelection(txid: String, vout: Int) {
        val utxoId = "$txid:$vout"
        _uiState.update { state ->
            val nextSelected = state.selectedUtxoIds.toMutableSet()
            if (nextSelected.contains(utxoId)) {
                nextSelected.remove(utxoId)
            } else {
                nextSelected.add(utxoId)
            }
            state.copy(selectedUtxoIds = nextSelected)
        }
    }

    private fun fetchBitcoinBalanceAndUtxos(address: String) {
        fetchBitcoinBalanceAndUtxos(listOf(address))
    }

    private fun fetchBitcoinBalanceAndUtxos(addresses: List<String>) {
        _uiState.update { it.copy(isFetchingBalance = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Sequential gap-limit scan: stop at the first unused address.
                // Typical wallets have 0–2 used addresses so this is far faster
                // than fetching all 10 in parallel and waiting for them all.
                val infos = mutableListOf<AddressInfo>()
                for ((index, address) in addresses.withIndex()) {
                    val info = BitcoinClient.fetchAddressInfo(address, index)
                    infos.add(info)
                    if (!info.isUsed) break  // gap-limit reached
                }

                val totalBalance = infos.sumOf { it.balance }
                val allUtxos = infos.flatMap { it.utxos }

                val firstUnusedIndex = infos.indexOfFirst { !it.isUsed }.let { if (it == -1) 0 else it }

                _uiState.update { state ->
                    val nextDerivedAddresses = state.derivedAddressesList?.mapValues { entry ->
                        entry.value.getOrElse(firstUnusedIndex) { entry.value.first() }
                    }

                    state.copy(
                        walletBalance = totalBalance,
                        walletUtxos = allUtxos,
                        selectedUtxoIds = allUtxos.map { utxo -> "${utxo.txid}:${utxo.vout}" }.toSet(),
                        activeAddressIndex = firstUnusedIndex,
                        derivedAddresses = nextDerivedAddresses ?: state.derivedAddresses,
                        isFetchingBalance = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isFetchingBalance = false,
                        readStatus = StatusMessage("Failed to fetch balance/UTXOs: ${e.message}", isError = true)
                    )
                }
            }
        }
    }

    fun initiateSend() {
        val state = _uiState.value
        val recipient = state.sendRecipient.trim()
        val amountStr = state.sendAmount.trim()
        val feeRateStr = state.sendFeeRate.trim()

        if (recipient.isBlank()) {
            _uiState.update { it.copy(broadcastError = "Recipient address cannot be empty.") }
            return
        }

        val amountSats = amountStr.toLongOrNull()
        if (amountSats == null || amountSats <= 0) {
            _uiState.update { it.copy(broadcastError = "Invalid amount. Must be a positive integer in Satoshis.") }
            return
        }

        val feeRate = feeRateStr.toLongOrNull()
        if (feeRate == null || feeRate <= 0) {
            _uiState.update { it.copy(broadcastError = "Invalid fee rate. Must be a positive integer in sat/vByte.") }
            return
        }

        val balance = state.walletBalance ?: 0L
        if (amountSats > balance) {
            _uiState.update { it.copy(broadcastError = "Insufficient balance. Available: $balance sat, trying to spend: $amountSats sat") }
            return
        }

        _uiState.update {
            it.copy(
                showConfirmBottomSheet = true,
                broadcastError = null,
                broadcastTxId = null
            )
        }
    }

    fun confirmSendAndScan() {
        val state = _uiState.value
        val password = state.confirmPassword
        if (password.length < MIN_PASSWORD_LENGTH) {
            _uiState.update { it.copy(broadcastError = "Password must be at least $MIN_PASSWORD_LENGTH characters.") }
            return
        }

        val amountSats = state.sendAmount.trim().toLongOrNull() ?: return
        val feeRate = state.sendFeeRate.trim().toLongOrNull() ?: return

        _uiState.update {
            it.copy(
                showConfirmBottomSheet = false,
                pendingScanAction = PendingScanAction.Sign(
                    password = password,
                    recipient = state.sendRecipient.trim(),
                    amount = amountSats,
                    feeRate = feeRate
                )
            )
        }
    }

    fun cancelSend() {
        _uiState.update {
            it.copy(
                showConfirmBottomSheet = false,
                confirmPassword = "",
                broadcastError = null
            )
        }
    }

    fun noteDetectedTag(tag: Tag) {
        val state = _uiState.value
        if (state.pendingScanAction != null || state.isProcessing) {
            return
        }

        viewModelScope.launch {
            val tagInfo = withContext(Dispatchers.IO) {
                tagService.inspectTag(tag)
            }
            _uiState.update { currentState ->
                val passiveStatus = passiveStatusForScreen(currentState.selectedScreen)
                when (currentState.selectedScreen) {
                    AppScreen.Read -> currentState.copy(
                        lastTagInfo = tagInfo,
                        readStatus = passiveStatus,
                    )

                    AppScreen.Write -> currentState.copy(
                        lastTagInfo = tagInfo,
                        writeStatus = passiveStatus,
                    )
                }
            }
        }
    }

    fun handlePassiveReadTag(tag: Tag) {
        val state = _uiState.value
        if (state.pendingScanAction != null || state.isProcessing) {
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProcessing = true,
                    readMessage = null,
                    derivedAddresses = null,
                    nostrNsec = null,
                    nostrNpub = null,
                    nostrPubkeyHex = null,
                    readStatus = StatusMessage("Reading the detected tag..."),
                )
            }

            val result = withContext(Dispatchers.IO) {
                try {
                    tagService.readEncryptedPayload(tag)
                } catch (error: Throwable) {
                    unexpectedPayloadFailureResult(tag, error)
                }
            }

            if (!result.isSuccess || result.encryptedPayload == null) {
                cachedReadPayload = null
                _uiState.update { currentState ->
                    currentState.copy(
                        isProcessing = false,
                        lastTagInfo = result.tagInfo,
                        readMessage = null,
                        readStatus = result.asStatusMessage(),
                    )
                }
                return@launch
            }

            cachedReadPayload = result.encryptedPayload
            _uiState.update { currentState ->
                currentState.copy(
                    isProcessing = false,
                    lastTagInfo = result.tagInfo,
                    readMessage = null,
                    readStatus =
                        if (currentState.readPassword.isBlank()) {
                            StatusMessage("Encrypted message found. Enter the password, then tap Try Password to decrypt it.")
                        } else {
                            StatusMessage("Encrypted message found. Trying the entered password...")
                        }
                )
            }
            scheduleCachedReadDecryption(_uiState.value.readPassword, immediate = true)
        }
    }

    fun handleTag(tag: Tag) {
        val action = _uiState.value.pendingScanAction ?: return
        val selectedIds = _uiState.value.selectedUtxoIds
        val walletUtxos = _uiState.value.walletUtxos ?: emptyList()

        viewModelScope.launch {
            _uiState.update { state ->
                when (action) {
                is PendingScanAction.Read -> {
                    state.copy(
                        pendingScanAction = null,
                        isProcessing = true,
                        readMessage = null,
                        readStatus = StatusMessage("Reading the encrypted message..."),
                    )
                }

                is PendingScanAction.Write -> {
                    state.copy(
                        pendingScanAction = null,
                        isProcessing = true,
                        writeStatus = StatusMessage("Encrypting and writing the message..."),
                    )
                }

                is PendingScanAction.Clear -> {
                    val status = StatusMessage("Clearing the tag...")
                    if (action.origin == AppScreen.Read) {
                        state.copy(
                            pendingScanAction = null,
                            isProcessing = true,
                            readMessage = null,
                            readStatus = status,
                        )
                    } else {
                        state.copy(
                            pendingScanAction = null,
                            isProcessing = true,
                            writeStatus = status,
                        )
                    }
                }

                is PendingScanAction.Sign -> {
                    state.copy(
                        pendingScanAction = null,
                        isProcessing = true,
                        readStatus = StatusMessage("Decrypting seed and signing transaction..."),
                    )
                }

                is PendingScanAction.WriteShare -> {
                    state.copy(
                        isProcessing = true,
                        writeStatus = StatusMessage("Encrypting and writing share ${action.currentIndex + 1}..."),
                    )
                }

                is PendingScanAction.ReadShare -> {
                    state.copy(
                        isProcessing = true,
                        readStatus = StatusMessage("Reading and decrypting share ${action.gathered.size + 1}..."),
                    )
                }
            }
        }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result =
                try {
                    when (action) {
                        is PendingScanAction.Read -> tagService.readEncryptedMessage(tag, action.password)
                        is PendingScanAction.Write -> tagService.writeEncryptedMessage(
                            tag = tag,
                            password = action.password,
                            message = action.message,
                            isDuress = action.isDuress,
                            emergencyPassword = action.emergencyPassword,
                            emergencyMessage = action.emergencyMessage
                        )
                        is PendingScanAction.Clear -> tagService.clearTag(tag)
                        is PendingScanAction.WriteShare -> {
                            val share = action.shares[action.currentIndex]
                            val shareWithK = ByteArray(share.size + 1)
                            shareWithK[0] = action.thresholdK.toByte()
                            System.arraycopy(share, 0, shareWithK, 1, share.size)
                            val encryptedPayload = SecureMessageCodec.encryptShareToPayload(shareWithK, action.password)
                            val writeResult = tagService.writeRawPayload(tag, encryptedPayload)
                            if (!writeResult.isSuccess) {
                                throw java.lang.Exception(writeResult.statusMessage)
                            }
                            writeResult
                        }
                        is PendingScanAction.ReadShare -> {
                            val readPayloadResult = tagService.readEncryptedPayload(tag)
                            if (!readPayloadResult.isSuccess || readPayloadResult.encryptedPayload == null) {
                                throw java.lang.Exception(readPayloadResult.statusMessage)
                            }
                            val decryptedPayloadWithK = SecureMessageCodec.decryptSharePayload(readPayloadResult.encryptedPayload, action.password)
                            if (decryptedPayloadWithK.size < 2) {
                                throw java.lang.Exception("Invalid SSS share payload decrypted.")
                            }
                            val k = decryptedPayloadWithK[0].toInt() and 0xff
                            val shareBytes = decryptedPayloadWithK.copyOfRange(1, decryptedPayloadWithK.size)
                            NfcOperationResult(
                                isSuccess = true,
                                statusMessage = "Share read successfully.",
                                decryptedMessage = "${k}:${shareBytes.toHex()}",
                                tagInfo = readPayloadResult.tagInfo
                            )
                        }
                        is PendingScanAction.Sign -> {
                            val readResult = tagService.readEncryptedMessage(tag, action.password)
                            if (!readResult.isSuccess || readResult.decryptedMessage == null) {
                                throw Exception(readResult.statusMessage)
                            }
                            val decryptedMessage = readResult.decryptedMessage
                            
                            val parsedPrivKey = KeyParser.parsePrivateKey(decryptedMessage)
                            val privateKeys = mutableMapOf<Int, PrivateKey>()
                            val (privKey, activeAddress) = if (parsedPrivKey != null) {
                                val pub = parsedPrivKey.publicKey()
                                val addr = when (_uiState.value.activeAddressType) {
                                    "Legacy (BIP-44)" -> pub.p2pkhAddress(Block.LivenetGenesisBlock.hash)
                                    "Nested SegWit (BIP-49)" -> pub.p2shOfP2wpkhAddress(Block.LivenetGenesisBlock.hash)
                                    "Taproot (BIP-86)" -> pub.p2trAddress(Block.LivenetGenesisBlock.hash)
                                    else -> pub.p2wpkhAddress(Block.LivenetGenesisBlock.hash)
                                }
                                Pair(parsedPrivKey, addr)
                            } else {
                                val words = decryptedMessage.trim().lowercase().split(Regex("\\s+"))
                                if (words.size != 12 && words.size != 24) {
                                    throw Exception("Invalid seed phrase or private key decrypted from tag.")
                                }
                                val seed = MnemonicCode.toSeed(words, "")
                                val master = DeterministicWallet.generate(seed)
                                val basePath = when (_uiState.value.activeAddressType) {
                                    "Legacy (BIP-44)" -> "m/44'/0'/0'/0/"
                                    "Nested SegWit (BIP-49)" -> "m/49'/0'/0'/0/"
                                    "Taproot (BIP-86)" -> "m/86'/0'/0'/0/"
                                    else -> "m/84'/0'/0'/0/"
                                }
                                for (index in 0..9) {
                                    privateKeys[index] = DeterministicWallet.derivePrivateKey(master, "$basePath$index").privateKey
                                }
                                val activeIdx = _uiState.value.activeAddressIndex
                                val activeDerived = DeterministicWallet.derivePrivateKey(master, "$basePath$activeIdx")
                                val activePub = activeDerived.publicKey
                                val addr = when (_uiState.value.activeAddressType) {
                                    "Legacy (BIP-44)" -> activePub.p2pkhAddress(Block.LivenetGenesisBlock.hash)
                                    "Nested SegWit (BIP-49)" -> activePub.p2shOfP2wpkhAddress(Block.LivenetGenesisBlock.hash)
                                    "Taproot (BIP-86)" -> activePub.p2trAddress(Block.LivenetGenesisBlock.hash)
                                    else -> activePub.p2wpkhAddress(Block.LivenetGenesisBlock.hash)
                                }
                                Pair(activeDerived.privateKey, addr)
                            }
                            
                            val selectedIds = _uiState.value.selectedUtxoIds
                            val utxos = (_uiState.value.walletUtxos ?: emptyList()).filter { utxo ->
                                selectedIds.contains("${utxo.txid}:${utxo.vout}")
                            }
                            if (utxos.isEmpty()) {
                                throw Exception("No UTXOs selected to sign.")
                            }

                            val rawTx = if (WalletEngine.isSilentPaymentAddress(action.recipient)) {
                                WalletEngine.buildAndSignSilentPaymentTransaction(
                                    utxos = utxos,
                                    silentPaymentAddress = action.recipient,
                                    amountSats = action.amount,
                                    changeAddress = activeAddress,
                                    feeRateSatsPerVByte = action.feeRate,
                                    privateKey = privKey,
                                    privateKeys = privateKeys
                                )
                            } else {
                                WalletEngine.buildAndSignTransaction(
                                    utxos = utxos,
                                    toAddress = action.recipient,
                                    amountSats = action.amount,
                                    changeAddress = activeAddress,
                                    feeRateSatsPerVByte = action.feeRate,
                                    privateKey = privKey,
                                    privateKeys = privateKeys
                                )
                            }

                            val txId = BitcoinClient.broadcastTransaction(rawTx)
                            
                            NfcOperationResult(
                                isSuccess = true,
                                statusMessage = "Transaction broadcasted successfully! TxID: $txId",
                                decryptedMessage = txId,
                                tagInfo = readResult.tagInfo
                            )
                        }
                    }
                } catch (error: Throwable) {
                    unexpectedFailureResult(tag, action, error)
                }
            _feedbackEvents.tryEmit(action.toFeedbackEvent(result))

            _uiState.update { state ->
                val nextState = state.copy(
                    isProcessing = false,
                    lastTagInfo = result.tagInfo,
                )

                when (action) {
                    is PendingScanAction.Read -> {
                        val derivedList = result.decryptedMessage?.let { deriveBitcoinAddressesAll(it) }
                        val derived = result.decryptedMessage?.let { deriveBitcoinAddresses(it) }
                        val activeType = state.activeAddressType
                        val validType = derivedList?.keys?.let { keys ->
                            if (activeType in keys) activeType else keys.firstOrNull() ?: activeType
                        } ?: activeType
                        val addresses = derivedList?.get(validType) ?: emptyList()
                        if (addresses.isNotEmpty()) {
                            fetchBitcoinBalanceAndUtxos(addresses)
                        } else {
                            derived?.get(validType)?.let { addr ->
                                fetchBitcoinBalanceAndUtxos(addr)
                            }
                        }
                        val nostrKeys = result.decryptedMessage?.let { NostrEngine.deriveNostrKeys(it) }
                        nextState.copy(
                            readPassword = "",
                            readMessage = result.decryptedMessage,
                            derivedAddresses = derived,
                            derivedAddressesList = derivedList,
                            activeAddressType = validType,
                            nostrNsec = nostrKeys?.nsec,
                            nostrNpub = nostrKeys?.npub,
                            nostrPubkeyHex = nostrKeys?.pubkeyHex,
                            readStatus = result.asStatusMessage(),
                        )
                    }

                    is PendingScanAction.Write -> {
                        if (result.isSuccess) {
                            nextState.copy(
                                writePassword = "",
                                writePasswordConfirmation = "",
                                writeMessage = "",
                                estimatedNdefWriteSizeBytes = 0,
                                estimatedMifareClassicWriteSizeBytes = 0,
                                writeStatus = result.asStatusMessage(),
                            )
                        } else {
                            nextState.copy(
                                writePassword = "",
                                writePasswordConfirmation = "",
                                writeStatus = result.asStatusMessage(),
                            )
                        }
                    }

                    is PendingScanAction.Clear -> {
                        if (action.origin == AppScreen.Read) {
                            nextState.copy(
                                readPassword = "",
                                readMessage = null,
                                derivedAddresses = null,
                                derivedAddressesList = null,
                                activeAddressIndex = 0,
                                nostrNsec = null,
                                nostrNpub = null,
                                nostrPubkeyHex = null,
                                readStatus = result.asStatusMessage(),
                            )
                        } else {
                            nextState.copy(
                                writePassword = "",
                                writePasswordConfirmation = "",
                                writeStatus = result.asStatusMessage(),
                            )
                        }
                    }

                    is PendingScanAction.Sign -> {
                        if (result.isSuccess) {
                            val addresses = state.derivedAddressesList?.get(state.activeAddressType)
                            if (addresses != null) {
                                fetchBitcoinBalanceAndUtxos(addresses)
                            } else {
                                val activeAddress = state.derivedAddresses?.get(state.activeAddressType)
                                if (activeAddress != null) {
                                    fetchBitcoinBalanceAndUtxos(activeAddress)
                                }
                            }
                            nextState.copy(
                                sendRecipient = "",
                                sendAmount = "",
                                confirmPassword = "",
                                broadcastTxId = result.decryptedMessage,
                                broadcastError = null,
                                readStatus = StatusMessage("Transaction sent successfully! TxID: ${result.decryptedMessage}")
                            )
                        } else {
                            nextState.copy(
                                confirmPassword = "",
                                broadcastError = result.statusMessage,
                                broadcastTxId = null,
                                readStatus = StatusMessage("Transaction failed: ${result.statusMessage}", isError = true)
                            )
                        }
                    }

                    is PendingScanAction.WriteShare -> {
                        if (result.isSuccess) {
                            if (action.currentIndex < action.shares.size - 1) {
                                val nextIndex = action.currentIndex + 1
                                nextState.copy(
                                    pendingScanAction = PendingScanAction.WriteShare(action.password, action.shares, nextIndex, action.thresholdK),
                                    writeStatus = StatusMessage("Share ${action.currentIndex + 1} written. Hold card ${nextIndex + 1} of ${action.shares.size} to write the next share."),
                                )
                            } else {
                                nextState.copy(
                                    pendingScanAction = null,
                                    writePassword = "",
                                    writePasswordConfirmation = "",
                                    writeMessage = "",
                                    estimatedNdefWriteSizeBytes = 0,
                                    estimatedMifareClassicWriteSizeBytes = 0,
                                    writeStatus = StatusMessage("Successfully wrote all ${action.shares.size} shares!"),
                                )
                            }
                        } else {
                            nextState.copy(
                                pendingScanAction = null,
                                writeStatus = StatusMessage("Failed to write share ${action.currentIndex + 1}: ${result.statusMessage}", isError = true),
                            )
                        }
                    }

                    is PendingScanAction.ReadShare -> {
                        if (result.isSuccess) {
                            val parts = result.decryptedMessage?.split(":")
                            if (parts != null && parts.size == 2) {
                                val k = parts[0].toIntOrNull() ?: 2
                                val newShareBytes = parts[1].hexToBytes()
                                val updatedGathered = action.gathered + listOfNotNull(newShareBytes)
                                if (updatedGathered.size >= k) {
                                    try {
                                        val secretBytes = ShamirSecretSharing.reconstruct(updatedGathered)
                                        val mnemonic = String(secretBytes, Charsets.UTF_8)
                                        val derivedList = deriveBitcoinAddressesAll(mnemonic)
                                        val derived = deriveBitcoinAddresses(mnemonic)
                                        if (derived != null) {
                                            val activeType = state.activeAddressType
                                            val addresses = derivedList?.get(activeType) ?: emptyList()
                                            if (addresses.isNotEmpty()) {
                                                fetchBitcoinBalanceAndUtxos(addresses)
                                            } else {
                                                derived[activeType]?.let { addr ->
                                                    fetchBitcoinBalanceAndUtxos(addr)
                                                }
                                            }
                                            val nostrKeys = NostrEngine.deriveNostrKeys(mnemonic)
                                            nextState.copy(
                                                pendingScanAction = null,
                                                isMultiNfcUnlock = false,
                                                readPassword = "",
                                                readMessage = mnemonic,
                                                derivedAddresses = derived,
                                                derivedAddressesList = derivedList,
                                                nostrNsec = nostrKeys?.nsec,
                                                nostrNpub = nostrKeys?.npub,
                                                nostrPubkeyHex = nostrKeys?.pubkeyHex,
                                                readStatus = StatusMessage("SSS Wallet successfully reassembled and unlocked."),
                                            )
                                        } else {
                                            nextState.copy(
                                                pendingScanAction = null,
                                                isMultiNfcUnlock = false,
                                                readStatus = StatusMessage("Failed to derive addresses from reassembled seed.", isError = true),
                                            )
                                        }
                                    } catch (e: Exception) {
                                        nextState.copy(
                                            pendingScanAction = null,
                                            isMultiNfcUnlock = false,
                                            readStatus = StatusMessage("Failed to reconstruct: ${e.message}. Ensure you scanned distinct password-matching shares.", isError = true),
                                        )
                                    }
                                } else {
                                    nextState.copy(
                                        pendingScanAction = PendingScanAction.ReadShare(action.password, updatedGathered, k),
                                        readStatus = StatusMessage("Share ${updatedGathered.size} of $k read. Hold card ${updatedGathered.size + 1} of $k against the phone to read the next share."),
                                    )
                                }
                            } else {
                                nextState.copy(
                                    pendingScanAction = null,
                                    isMultiNfcUnlock = false,
                                    readStatus = StatusMessage("Failed to parse SSS share payload.", isError = true),
                                )
                            }
                        } else {
                            nextState.copy(
                                pendingScanAction = null,
                                isMultiNfcUnlock = false,
                                readStatus = StatusMessage("Failed to read SSS share: ${result.statusMessage}", isError = true),
                            )
                        }
                    }
                }
            }
            val stateAfter = _uiState.value
            if (stateAfter.readMessage != null && (action is PendingScanAction.Read || action is PendingScanAction.ReadShare)) {
                syncEcashWallet()
            }
        }
    }

    private fun updateReadStatus(text: String, isError: Boolean) {
        _uiState.update { it.copy(readStatus = StatusMessage(text, isError)) }
    }

    private fun updateWriteStatus(text: String, isError: Boolean) {
        _uiState.update { it.copy(writeStatus = StatusMessage(text, isError)) }
    }

    private fun updateStatusForScreen(screen: AppScreen, text: String, isError: Boolean) {
        when (screen) {
            AppScreen.Read -> updateReadStatus(text, isError)
            AppScreen.Write -> updateWriteStatus(text, isError)
        }
    }

    private fun scheduleCachedReadDecryption(password: String, immediate: Boolean = false) {
        readPasswordAttemptJob?.cancel()
        if (password.isBlank() || cachedReadPayload == null || _uiState.value.isProcessing) {
            return
        }

        readPasswordAttemptJob = viewModelScope.launch(Dispatchers.Default) {
            if (!immediate) {
                delay(readPasswordAttemptDelayMs)
            }
            if (_uiState.value.readPassword != password || cachedReadPayload == null) {
                return@launch
            }
            attemptCachedReadDecryption(password)
        }
    }

    private fun attemptCachedReadDecryption(password: String) {
        val payload = cachedReadPayload ?: return
        val tagInfo = _uiState.value.lastTagInfo ?: TagInfo(
            capacityBytes = null,
            isWritable = null,
            technologies = emptyList(),
        )

        _uiState.update {
            it.copy(
                isProcessing = true,
                readMessage = null,
                readStatus = StatusMessage("Trying the entered password..."),
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            val result =
                try {
                    val message = SecureMessageCodec.decryptPayload(payload, password)
                    NfcOperationResult(
                        isSuccess = true,
                        statusMessage =
                            when (tagInfo.storageBackend) {
                                StorageBackend.MifareClassicRaw -> "Message decrypted successfully from raw MIFARE Classic storage."
                                StorageBackend.Ndef, StorageBackend.Unknown -> "Message decrypted successfully from NDEF."
                            },
                        decryptedMessage = message,
                        tagInfo = tagInfo,
                    )
                } catch (error: InvalidPasswordException) {
                    NfcOperationResult(
                        isSuccess = false,
                        statusMessage = "Wrong password or corrupted encrypted message.",
                        tagInfo = tagInfo,
                    )
                } catch (error: Throwable) {
                    unexpectedFailureResultForTagInfo(tagInfo, "read", error)
                }

            if (_uiState.value.readPassword != password) {
                return@launch
            }

            val derivedList = result.decryptedMessage?.let { deriveBitcoinAddressesAll(it) }
            val derived = result.decryptedMessage?.let { deriveBitcoinAddresses(it) }
            val activeType = _uiState.value.activeAddressType
            val validType = derivedList?.keys?.let { keys ->
                if (activeType in keys) activeType else keys.firstOrNull() ?: activeType
            } ?: activeType
            val addresses = derivedList?.get(validType) ?: emptyList()
            if (addresses.isNotEmpty()) {
                fetchBitcoinBalanceAndUtxos(addresses)
            } else {
                derived?.get(validType)?.let { addr ->
                    fetchBitcoinBalanceAndUtxos(addr)
                }
            }
            val nostrKeys = result.decryptedMessage?.let { NostrEngine.deriveNostrKeys(it) }

            _uiState.update { state ->
                state.copy(
                    isProcessing = false,
                    readMessage = result.decryptedMessage,
                    derivedAddresses = derived,
                    derivedAddressesList = derivedList,
                    activeAddressType = validType,
                    nostrNsec = nostrKeys?.nsec,
                    nostrNpub = nostrKeys?.npub,
                    nostrPubkeyHex = nostrKeys?.pubkeyHex,
                    readStatus = result.asStatusMessage(),
                    lastTagInfo = result.tagInfo,
                )
            }

            if (result.isSuccess && result.decryptedMessage != null) {
                val request = _uiState.value.nostrSignerRequest
                if (request != null && nostrKeys != null) {
                    approveNostrSignerRequest(nostrKeys.privkeyHex)
                }
                syncEcashWallet()
            }
        }
    }

    fun generateMnemonic(wordCount: Int) {
        val mnemonic = Bip39Compressor.generateMnemonic(wordCount).joinToString(" ")
        _uiState.update { it.copy(generatedMnemonic = mnemonic) }
    }

    fun useGeneratedMnemonic() {
        val mnemonic = _uiState.value.generatedMnemonic ?: return
        _uiState.update {
            it.copy(
                writeMessage = mnemonic,
                generatedMnemonic = null,
                estimatedNdefWriteSizeBytes = estimateNdefWriteSize(mnemonic),
                estimatedMifareClassicWriteSizeBytes = estimateMifareClassicWriteSize(mnemonic),
            )
        }
    }

    fun clearGeneratedMnemonic() {
        _uiState.update { it.copy(generatedMnemonic = null) }
    }

    private fun deriveBitcoinAddresses(inputStr: String): Map<String, String>? {
        val clean = inputStr.trim()
        if (clean.isEmpty()) return null

        // 1. nsec → Nostr Taproot only
        val nsecPrivKey = KeyParser.parseNsec(clean)
        if (nsecPrivKey != null) {
            return try {
                val pubkey = nsecPrivKey.publicKey()
                mapOf("Nostr Taproot (nsec)" to pubkey.p2trAddress(Block.LivenetGenesisBlock.hash))
            } catch (e: Exception) { null }
        }

        // 2. Other raw private key (WIF/Hex/xprv) → all 4 script types
        val parsedPrivKey = KeyParser.parsePrivateKey(clean)
        if (parsedPrivKey != null) {
            return try {
                val pubkey = parsedPrivKey.publicKey()
                mapOf(
                    "Legacy (BIP-44)" to pubkey.p2pkhAddress(Block.LivenetGenesisBlock.hash),
                    "Nested SegWit (BIP-49)" to pubkey.p2shOfP2wpkhAddress(Block.LivenetGenesisBlock.hash),
                    "Native SegWit (BIP-84)" to pubkey.p2wpkhAddress(Block.LivenetGenesisBlock.hash),
                    "Taproot (BIP-86)" to pubkey.p2trAddress(Block.LivenetGenesisBlock.hash)
                )
            } catch (e: Exception) {
                null
            }
        }

        // 3. BIP-39 mnemonic → 4 HD types + Nostr Taproot (5th)
        val words = clean.lowercase().split(Regex("\\s+"))
        if (words.size != 12 && words.size != 24) return null
        return try {
            val seed = MnemonicCode.toSeed(words, "")
            val master = DeterministicWallet.generate(seed)

            val paths = mapOf(
                "Legacy (BIP-44)" to "m/44'/0'/0'/0/0",
                "Nested SegWit (BIP-49)" to "m/49'/0'/0'/0/0",
                "Native SegWit (BIP-84)" to "m/84'/0'/0'/0/0",
                "Taproot (BIP-86)" to "m/86'/0'/0'/0/0"
            )

            val hdAddresses = paths.mapValues { (_, path) ->
                val derived = DeterministicWallet.derivePrivateKey(master, path)
                val pubkey = derived.publicKey
                when {
                    path.contains("84'") -> pubkey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)
                    path.contains("49'") -> pubkey.p2shOfP2wpkhAddress(Block.LivenetGenesisBlock.hash)
                    path.contains("86'") -> pubkey.p2trAddress(Block.LivenetGenesisBlock.hash)
                    else -> pubkey.p2pkhAddress(Block.LivenetGenesisBlock.hash)
                }
            }

            // 5th type: Taproot from the NIP-06 Nostr key derived from this seed
            val nostrDerived = DeterministicWallet.derivePrivateKey(master, NostrEngine.NOSTR_DERIVATION_PATH)
            val nostrTaproot = nostrDerived.publicKey.p2trAddress(Block.LivenetGenesisBlock.hash)

            hdAddresses + mapOf("Nostr Taproot" to nostrTaproot)
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveBitcoinAddressesAll(inputStr: String): Map<String, List<String>>? {
        val clean = inputStr.trim()
        if (clean.isEmpty()) return null

        // 1. nsec → Nostr Taproot only (single address, no HD tree)
        val nsecPrivKey = KeyParser.parseNsec(clean)
        if (nsecPrivKey != null) {
            return try {
                val pubkey = nsecPrivKey.publicKey()
                mapOf("Nostr Taproot (nsec)" to listOf(pubkey.p2trAddress(Block.LivenetGenesisBlock.hash)))
            } catch (e: Exception) { null }
        }

        // 2. Other raw private key (WIF/Hex/xprv) → single address per type (no HD sweep)
        val parsedPrivKey = KeyParser.parsePrivateKey(clean)
        if (parsedPrivKey != null) {
            return try {
                val pubkey = parsedPrivKey.publicKey()
                mapOf(
                    "Legacy (BIP-44)" to listOf(pubkey.p2pkhAddress(Block.LivenetGenesisBlock.hash)),
                    "Nested SegWit (BIP-49)" to listOf(pubkey.p2shOfP2wpkhAddress(Block.LivenetGenesisBlock.hash)),
                    "Native SegWit (BIP-84)" to listOf(pubkey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)),
                    "Taproot (BIP-86)" to listOf(pubkey.p2trAddress(Block.LivenetGenesisBlock.hash))
                )
            } catch (e: Exception) {
                null
            }
        }

        // 3. BIP-39 mnemonic → 4 HD types (10 addresses each) + Nostr Taproot (1 address)
        val words = clean.lowercase().split(Regex("\\s+"))
        if (words.size != 12 && words.size != 24) return null
        return try {
            val seed = MnemonicCode.toSeed(words, "")
            val master = DeterministicWallet.generate(seed)

            val basePaths = mapOf(
                "Legacy (BIP-44)" to "m/44'/0'/0'/0/",
                "Nested SegWit (BIP-49)" to "m/49'/0'/0'/0/",
                "Native SegWit (BIP-84)" to "m/84'/0'/0'/0/",
                "Taproot (BIP-86)" to "m/86'/0'/0'/0/"
            )

            val hdAddresses = basePaths.mapValues { (_, basePath) ->
                (0..9).map { index ->
                    val path = "$basePath$index"
                    val derived = DeterministicWallet.derivePrivateKey(master, path)
                    val pubkey = derived.publicKey
                    when {
                        path.contains("84'") -> pubkey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)
                        path.contains("49'") -> pubkey.p2shOfP2wpkhAddress(Block.LivenetGenesisBlock.hash)
                        path.contains("86'") -> pubkey.p2trAddress(Block.LivenetGenesisBlock.hash)
                        else -> pubkey.p2pkhAddress(Block.LivenetGenesisBlock.hash)
                    }
                }
            }

            // 5th type: Taproot from the NIP-06 Nostr key (single address, deterministic)
            val nostrDerived = DeterministicWallet.derivePrivateKey(master, NostrEngine.NOSTR_DERIVATION_PATH)
            val nostrTaproot = nostrDerived.publicKey.p2trAddress(Block.LivenetGenesisBlock.hash)

            hdAddresses + mapOf("Nostr Taproot" to listOf(nostrTaproot))
        } catch (e: Exception) {
            null
        }
    }


    private fun unexpectedFailureResult(
        tag: Tag,
        action: PendingScanAction,
        error: Throwable,
    ): NfcOperationResult {
        return unexpectedFailureResultForTagInfo(tagService.inspectTag(tag), action.description, error)
    }

    private fun unexpectedFailureResultForTagInfo(
        tagInfo: TagInfo,
        action: String,
        error: Throwable,
    ): NfcOperationResult {
        val diagnostic = NfcDiagnostics.unexpectedFailure(action, error)
        return NfcOperationResult(
            isSuccess = false,
            statusMessage = diagnostic.statusMessage,
            diagnosticDetail = diagnostic.detail,
            tagInfo = tagInfo,
        )
    }

    private fun unexpectedPayloadFailureResult(tag: Tag, error: Throwable): NfcPayloadReadResult {
        val diagnostic = NfcDiagnostics.unexpectedFailure("read", error)
        return NfcPayloadReadResult(
            isSuccess = false,
            statusMessage = diagnostic.statusMessage,
            diagnosticDetail = diagnostic.detail,
            tagInfo = tagService.inspectTag(tag),
        )
    }

    private fun passiveStatusForScreen(screen: AppScreen): StatusMessage =
        when (screen) {
            AppScreen.Read ->
                StatusMessage("Card detected. If it contains a message, enter the password to decrypt it or tap Clear Card to erase it.")

            AppScreen.Write ->
                StatusMessage("Card detected. Tap Write to Card to overwrite it or Clear Card to erase it.")
        }

    private fun estimateNdefWriteSize(message: String): Int {
        if (message.isBlank()) {
            return 0
        }
        return SecureMessageCodec.estimateNdefMessageSize(message)
    }

    private fun estimateMifareClassicWriteSize(message: String): Int {
        if (message.isBlank()) {
            return 0
        }
        return SecureMessageCodec.estimateMifareClassicStorageSize(message)
    }

    private fun PendingScanAction.toFeedbackEvent(result: NfcOperationResult): UserFeedbackEvent {
        if (!result.isSuccess) {
            return UserFeedbackEvent(result.statusMessage, FeedbackTone.Error)
        }

        val message = when (this) {
            is PendingScanAction.Read -> "Message decrypted."
            is PendingScanAction.Write -> "Message written."
            is PendingScanAction.Clear -> "Card cleared."
            is PendingScanAction.Sign -> "Transaction broadcasted."
            is PendingScanAction.WriteShare -> "Share ${currentIndex + 1} written."
            is PendingScanAction.ReadShare -> "Share ${gathered.size + 1} read."
        }
        return UserFeedbackEvent(message, FeedbackTone.Success)
    }

    private val PendingScanAction.description: String
        get() = when (this) {
            is PendingScanAction.Read -> "read"
            is PendingScanAction.Write -> "write"
            is PendingScanAction.Clear -> "clear"
            is PendingScanAction.Sign -> "sign"
            is PendingScanAction.WriteShare -> "write share"
            is PendingScanAction.ReadShare -> "read share"
        }

    private fun NfcOperationResult.asStatusMessage(): StatusMessage =
        StatusMessage(
            text = statusMessage,
            isError = !isSuccess,
            detail = diagnosticDetail,
        )

    private fun NfcPayloadReadResult.asStatusMessage(): StatusMessage =
        StatusMessage(
            text = statusMessage,
            isError = !isSuccess,
            detail = diagnosticDetail,
        )

    // --- Cashu Wallet Event Handlers and Helpers ---

    fun onCashuMintUrlChanged(url: String) {
        _uiState.update { it.copy(cashuMintUrl = url, cashuError = null) }
        persistRestorableState(_uiState.value)
        syncEcashWallet()
    }

    fun syncEcashWallet() {
        val seed = deriveCashuMasterSeed() ?: return
        val mintUrl = _uiState.value.cashuMintUrl
        _uiState.update { it.copy(cashuLoading = true, cashuError = null) }
        viewModelScope.launch {
            try {
                val keysets = CashuClient.fetchKeysets(mintUrl)
                val satKeyset = keysets.firstOrNull { it.unit == "sat" }
                    ?: throw Exception("No sat keyset found at mint")
                
                val candidateProofs = mutableListOf<CashuProof>()
                val secretMap = mutableMapOf<String, String>()
                val rMap = mutableMapOf<String, BigInteger>()
                
                var currentCounter = 0L
                var maxUsedCounter = -1L
                var consecutiveEmptyBatches = 0
                val batchSize = 100
                
                while (consecutiveEmptyBatches < 3) {
                    val blindedMessages = mutableListOf<BlindedMessage>()
                    
                    for (i in 0 until batchSize) {
                        val idx = currentCounter + i
                        val (secret, r) = CashuEngine.deriveSecretAndR(seed, satKeyset.id, idx)
                        val B_point = CashuEngine.blind(secret, r)
                        val B_hex = B_point.toHex()
                        
                        secretMap[B_hex] = secret
                        rMap[B_hex] = r
                        blindedMessages.add(BlindedMessage(amount = 1L, id = satKeyset.id, B_ = B_hex))
                    }
                    
                    val restored = CashuClient.restoreSignatures(mintUrl, blindedMessages)
                    if (restored.isEmpty()) {
                        consecutiveEmptyBatches++
                    } else {
                        consecutiveEmptyBatches = 0
                        
                        for (sig in restored) {
                            val r = rMap[sig.b_]
                            val secret = secretMap[sig.b_]
                            if (r != null && secret != null) {
                                val K_hex = satKeyset.keys[sig.amount]
                                if (K_hex != null) {
                                    val K_point = Secp256k1Math.parsePoint(K_hex)
                                    val C_prime = Secp256k1Math.parsePoint(sig.C_)
                                    val C_point = CashuEngine.unblind(C_prime, r, K_point)
                                    
                                    candidateProofs.add(
                                        CashuProof(
                                            amount = sig.amount,
                                            id = sig.id,
                                            secret = secret,
                                            C = C_point.toHex()
                                        )
                                    )
                                }
                            }
                        }
                        
                        for (i in 0 until batchSize) {
                            val idx = currentCounter + i
                            val (secret, r) = CashuEngine.deriveSecretAndR(seed, satKeyset.id, idx)
                            val B_point = CashuEngine.blind(secret, r)
                            val B_hex = B_point.toHex()
                            if (restored.any { it.b_ == B_hex }) {
                                if (idx > maxUsedCounter) {
                                    maxUsedCounter = idx
                                }
                            }
                        }
                    }
                    
                    currentCounter += batchSize
                }
                
                val unspentProofs = if (candidateProofs.isNotEmpty()) {
                    val ys = candidateProofs.map { proof ->
                        CashuEngine.deriveYPointHex(proof.secret)
                    }
                    val states = CashuClient.checkTokenStates(mintUrl, ys)
                    val stateMap = states.associate { it.Y to it.state }
                    
                    candidateProofs.filter { proof ->
                        val y = CashuEngine.deriveYPointHex(proof.secret)
                        stateMap[y] == "UNSPENT"
                    }
                } else {
                    emptyList()
                }
                
                val finalCounter = maxOf(maxUsedCounter + 1, _uiState.value.cashuCounter)
                
                _uiState.update { state ->
                    state.copy(
                        cashuProofs = unspentProofs,
                        cashuBalanceSat = unspentProofs.sumOf { it.amount },
                        cashuCounter = finalCounter,
                        cashuLoading = false
                    )
                }
                persistRestorableState(_uiState.value)
            } catch (e: Exception) {
                _uiState.update { it.copy(cashuError = "Sync Error: ${e.message}", cashuLoading = false) }
            }
        }
    }

    fun onCashuMintAmountInputChanged(input: String) {
        _uiState.update { it.copy(cashuMintAmountInput = input) }
    }

    fun onCashuSendAmountInputChanged(input: String) {
        _uiState.update { it.copy(cashuSendAmountInput = input) }
    }

    fun onCashuReceiveTokenInputChanged(input: String) {
        _uiState.update { it.copy(cashuReceiveTokenInput = input) }
    }

    fun onCashuMeltInvoiceChanged(input: String) {
        _uiState.update { it.copy(cashuMeltInvoice = input) }
    }

    fun requestCashuMintQuote() {
        val amount = _uiState.value.cashuMintAmountInput.toLongOrNull() ?: return
        if (amount <= 0) return
        _uiState.update { it.copy(cashuLoading = true, cashuError = null, cashuMintQuote = null) }
        viewModelScope.launch {
            try {
                val quote = CashuClient.requestMintQuote(_uiState.value.cashuMintUrl, amount)
                _uiState.update { it.copy(cashuMintQuote = quote, cashuMintQuoteAmountSat = amount, cashuLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(cashuError = "Mint Quote Error: ${e.message}", cashuLoading = false) }
            }
        }
    }

    fun checkAndClaimCashuMint() {
        val quote = _uiState.value.cashuMintQuote ?: return
        val amount = _uiState.value.cashuMintQuoteAmountSat
        val mintUrl = _uiState.value.cashuMintUrl
        _uiState.update { it.copy(cashuLoading = true, cashuError = null) }
        
        viewModelScope.launch {
            try {
                val status = CashuClient.checkMintQuote(mintUrl, quote.quote)
                if (status.state != "PAID") {
                    _uiState.update { it.copy(cashuError = "Invoice is not paid yet (State: ${status.state})", cashuLoading = false) }
                    return@launch
                }
                
                val keysets = CashuClient.fetchKeysets(mintUrl)
                val satKeyset = keysets.firstOrNull { it.unit == "sat" } 
                    ?: throw Exception("No sat keyset found at mint")
                    
                val seed = deriveCashuMasterSeed() 
                    ?: throw Exception("Wallet is locked or no seed phrase found")
                val parts = splitAmount(amount)
                val secrets = mutableListOf<String>()
                val rs = mutableListOf<BigInteger>()
                val blindedMessages = mutableListOf<BlindedMessage>()
                
                var currentCounter = _uiState.value.cashuCounter
                
                parts.forEach { partAmount ->
                    val (secret, r) = CashuEngine.deriveSecretAndR(seed, satKeyset.id, currentCounter)
                    val B_point = CashuEngine.blind(secret, r)
                    val B_hex = B_point.toHex()
                    
                    secrets.add(secret)
                    rs.add(r)
                    blindedMessages.add(BlindedMessage(amount = partAmount, id = satKeyset.id, B_ = B_hex))
                    currentCounter++
                }
                
                val signatures = CashuClient.mintTokens(mintUrl, quote.quote, blindedMessages)
                val newProofs = signatures.mapIndexed { idx, sig ->
                    val r = rs[idx]
                    val K_hex = satKeyset.keys[sig.amount] 
                        ?: throw Exception("No public key found for amount ${sig.amount}")
                    val K_point = Secp256k1Math.parsePoint(K_hex)
                    val C_prime = Secp256k1Math.parsePoint(sig.C_)
                    val C_point = CashuEngine.unblind(C_prime, r, K_point)
                    
                    CashuProof(
                        amount = sig.amount,
                        id = sig.id,
                        secret = secrets[idx],
                        C = C_point.toHex()
                    )
                }
                
                val updatedProofs = _uiState.value.cashuProofs + newProofs
                _uiState.update { it.copy(
                    cashuProofs = updatedProofs,
                    cashuBalanceSat = updatedProofs.sumOf { p -> p.amount },
                    cashuCounter = currentCounter,
                    cashuMintQuote = null,
                    cashuMintQuoteAmountSat = 0L,
                    cashuMintAmountInput = "",
                    cashuLoading = false
                ) }
                persistRestorableState(_uiState.value)
            } catch (e: Exception) {
                _uiState.update { it.copy(cashuError = "Mint Error: ${e.message}", cashuLoading = false) }
            }
        }
    }

    fun clearCashuMintQuote() {
        _uiState.update { it.copy(cashuMintQuote = null, cashuMintQuoteAmountSat = 0L, cashuError = null) }
    }

    fun generateCashuSendToken() {
        val amount = _uiState.value.cashuSendAmountInput.toLongOrNull() ?: return
        if (amount <= 0) return
        
        val currentProofs = _uiState.value.cashuProofs
        val totalAvailable = currentProofs.sumOf { it.amount }
        if (totalAvailable < amount) {
            _uiState.update { it.copy(cashuError = "Insufficient balance (Have $totalAvailable, need $amount)") }
            return
        }
        
        _uiState.update { it.copy(cashuLoading = true, cashuError = null, cashuGeneratedToken = null) }
        
        viewModelScope.launch {
            try {
                val mintUrl = _uiState.value.cashuMintUrl
                
                val selectedInputs = mutableListOf<CashuProof>()
                var accumulated = 0L
                for (proof in currentProofs.sortedByDescending { it.amount }) {
                    selectedInputs.add(proof)
                    accumulated += proof.amount
                    if (accumulated >= amount) break
                }
                
                val changeAmount = accumulated - amount
                
                val keysets = CashuClient.fetchKeysets(mintUrl)
                val satKeyset = keysets.firstOrNull { it.unit == "sat" } 
                    ?: throw Exception("No sat keyset found at mint")
                    
                val seed = deriveCashuMasterSeed() 
                    ?: throw Exception("Wallet is locked or no seed phrase found")
                val sendParts = splitAmount(amount)
                val changeParts = splitAmount(changeAmount)
                val allParts = sendParts + changeParts
                
                val secrets = mutableListOf<String>()
                val rs = mutableListOf<BigInteger>()
                val blindedMessages = mutableListOf<BlindedMessage>()
                
                var currentCounter = _uiState.value.cashuCounter
                
                allParts.forEach { partAmount ->
                    val (secret, r) = CashuEngine.deriveSecretAndR(seed, satKeyset.id, currentCounter)
                    val B_point = CashuEngine.blind(secret, r)
                    val B_hex = B_point.toHex()
                    
                    secrets.add(secret)
                    rs.add(r)
                    blindedMessages.add(BlindedMessage(amount = partAmount, id = satKeyset.id, B_ = B_hex))
                    currentCounter++
                }
                
                val signatures = CashuClient.swapTokens(mintUrl, selectedInputs, blindedMessages)
                val newProofs = signatures.mapIndexed { idx, sig ->
                    val r = rs[idx]
                    val K_hex = satKeyset.keys[sig.amount] 
                        ?: throw Exception("No public key found for amount ${sig.amount}")
                    val K_point = Secp256k1Math.parsePoint(K_hex)
                    val C_prime = Secp256k1Math.parsePoint(sig.C_)
                    val C_point = CashuEngine.unblind(C_prime, r, K_point)
                    
                    CashuProof(
                        amount = sig.amount,
                        id = sig.id,
                        secret = secrets[idx],
                        C = C_point.toHex()
                    )
                }
                
                val sendProofs = newProofs.take(sendParts.size)
                val changeProofs = newProofs.drop(sendParts.size)
                
                val remainingProofs = currentProofs.filter { it !in selectedInputs }
                val updatedProofs = remainingProofs + changeProofs
                
                val tokenStr = serializeCashuTokenV3(mintUrl, sendProofs)
                
                _uiState.update { it.copy(
                    cashuProofs = updatedProofs,
                    cashuBalanceSat = updatedProofs.sumOf { p -> p.amount },
                    cashuCounter = currentCounter,
                    cashuGeneratedToken = tokenStr,
                    cashuSendAmountInput = "",
                    cashuLoading = false
                ) }
                persistRestorableState(_uiState.value)
            } catch (e: Exception) {
                _uiState.update { it.copy(cashuError = "Send Error: ${e.message}", cashuLoading = false) }
            }
        }
    }

    fun claimCashuToken() {
        val tokenStr = _uiState.value.cashuReceiveTokenInput.trim()
        if (tokenStr.isEmpty()) return
        _uiState.update { it.copy(cashuLoading = true, cashuError = null) }
        
        viewModelScope.launch {
            try {
                val parsed = parseCashuTokenV3(tokenStr)
                val tokenMint = parsed.mint
                val tokenProofs = parsed.proofs
                val amount = tokenProofs.sumOf { it.amount }
                
                val keysets = CashuClient.fetchKeysets(tokenMint)
                val satKeyset = keysets.firstOrNull { it.unit == "sat" } 
                    ?: throw Exception("No sat keyset found at token mint")
                    
                val seed = deriveCashuMasterSeed() 
                    ?: throw Exception("Wallet is locked or no seed phrase found")
                val parts = splitAmount(amount)
                val secrets = mutableListOf<String>()
                val rs = mutableListOf<BigInteger>()
                val blindedMessages = mutableListOf<BlindedMessage>()
                
                var currentCounter = _uiState.value.cashuCounter
                
                parts.forEach { partAmount ->
                    val (secret, r) = CashuEngine.deriveSecretAndR(seed, satKeyset.id, currentCounter)
                    val B_point = CashuEngine.blind(secret, r)
                    val B_hex = B_point.toHex()
                    
                    secrets.add(secret)
                    rs.add(r)
                    blindedMessages.add(BlindedMessage(amount = partAmount, id = satKeyset.id, B_ = B_hex))
                    currentCounter++
                }
                
                val signatures = CashuClient.swapTokens(tokenMint, tokenProofs, blindedMessages)
                val newProofs = signatures.mapIndexed { idx, sig ->
                    val r = rs[idx]
                    val K_hex = satKeyset.keys[sig.amount] 
                        ?: throw Exception("No public key found for amount ${sig.amount}")
                    val K_point = Secp256k1Math.parsePoint(K_hex)
                    val C_prime = Secp256k1Math.parsePoint(sig.C_)
                    val C_point = CashuEngine.unblind(C_prime, r, K_point)
                    
                    CashuProof(
                        amount = sig.amount,
                        id = sig.id,
                        secret = secrets[idx],
                        C = C_point.toHex()
                    )
                }
                
                val updatedProofs = _uiState.value.cashuProofs + newProofs
                _uiState.update { it.copy(
                    cashuProofs = updatedProofs,
                    cashuBalanceSat = updatedProofs.sumOf { p -> p.amount },
                    cashuCounter = currentCounter,
                    cashuReceiveTokenInput = "",
                    cashuLoading = false
                ) }
                persistRestorableState(_uiState.value)
            } catch (e: Exception) {
                _uiState.update { it.copy(cashuError = "Claim Error: ${e.message}", cashuLoading = false) }
            }
        }
    }

    fun requestCashuMeltQuote() {
        val invoice = _uiState.value.cashuMeltInvoice.trim()
        if (invoice.isEmpty()) return
        _uiState.update { it.copy(cashuLoading = true, cashuError = null, cashuMeltQuote = null, cashuMeltSuccessPreimage = null) }
        
        viewModelScope.launch {
            try {
                val quote = CashuClient.requestMeltQuote(_uiState.value.cashuMintUrl, invoice)
                _uiState.update { it.copy(cashuMeltQuote = quote, cashuLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(cashuError = "Melt Quote Error: ${e.message}", cashuLoading = false) }
            }
        }
    }

    fun confirmAndExecuteMelt() {
        val quote = _uiState.value.cashuMeltQuote ?: return
        val mintUrl = _uiState.value.cashuMintUrl
        val currentProofs = _uiState.value.cashuProofs
        val totalNeeded = quote.amount + quote.feeReserve
        
        val totalAvailable = currentProofs.sumOf { it.amount }
        if (totalAvailable < totalNeeded) {
            _uiState.update { it.copy(cashuError = "Insufficient balance to cover payment and fee reserve (Have $totalAvailable, need $totalNeeded)") }
            return
        }
        
        _uiState.update { it.copy(cashuLoading = true, cashuError = null) }
        
        viewModelScope.launch {
            try {
                val selectedInputs = mutableListOf<CashuProof>()
                var accumulated = 0L
                for (proof in currentProofs.sortedByDescending { it.amount }) {
                    selectedInputs.add(proof)
                    accumulated += proof.amount
                    if (accumulated >= totalNeeded) break
                }
                
                val changeAmount = accumulated - totalNeeded
                val changeParts = splitAmount(changeAmount)
                val secrets = mutableListOf<String>()
                val rs = mutableListOf<BigInteger>()
                val blindedMessages = mutableListOf<BlindedMessage>()
                
                var currentCounter = _uiState.value.cashuCounter
                
                if (changeParts.isNotEmpty()) {
                    val seed = deriveCashuMasterSeed() 
                        ?: throw Exception("Wallet is locked or no seed phrase found")
                    val keysets = CashuClient.fetchKeysets(mintUrl)
                    val satKeyset = keysets.firstOrNull { it.unit == "sat" } 
                        ?: throw Exception("No sat keyset found at mint")
                        
                    changeParts.forEach { partAmount ->
                        val (secret, r) = CashuEngine.deriveSecretAndR(seed, satKeyset.id, currentCounter)
                        val B_point = CashuEngine.blind(secret, r)
                        val B_hex = B_point.toHex()
                        
                        secrets.add(secret)
                        rs.add(r)
                        blindedMessages.add(BlindedMessage(amount = partAmount, id = satKeyset.id, B_ = B_hex))
                        currentCounter++
                    }
                }
                
                val response = CashuClient.meltTokens(
                    mintUrl = mintUrl,
                    quoteId = quote.quote,
                    inputs = selectedInputs,
                    outputs = if (blindedMessages.isNotEmpty()) blindedMessages else null
                )
                
                if (!response.paid) {
                    throw Exception("Mint failed to execute Lightning payment")
                }
                
                val newProofs = mutableListOf<CashuProof>()
                if (response.change.isNotEmpty()) {
                    val keysets = CashuClient.fetchKeysets(mintUrl)
                    val satKeyset = keysets.firstOrNull { it.unit == "sat" } 
                        ?: throw Exception("No sat keyset found at mint")
                        
                    response.change.forEachIndexed { idx, sig ->
                        val r = rs[idx]
                        val K_hex = satKeyset.keys[sig.amount] 
                            ?: throw Exception("No public key found for amount ${sig.amount}")
                        val K_point = Secp256k1Math.parsePoint(K_hex)
                        val C_prime = Secp256k1Math.parsePoint(sig.C_)
                        val C_point = CashuEngine.unblind(C_prime, r, K_point)
                        
                        newProofs.add(
                            CashuProof(
                                amount = sig.amount,
                                id = sig.id,
                                secret = secrets[idx],
                                C = C_point.toHex()
                            )
                        )
                    }
                }
                
                val remainingProofs = currentProofs.filter { it !in selectedInputs }
                val updatedProofs = remainingProofs + newProofs
                
                _uiState.update { it.copy(
                    cashuProofs = updatedProofs,
                    cashuBalanceSat = updatedProofs.sumOf { p -> p.amount },
                    cashuCounter = currentCounter,
                    cashuMeltQuote = null,
                    cashuMeltInvoice = "",
                    cashuMeltSuccessPreimage = response.preimage ?: "Success (no preimage)",
                    cashuLoading = false
                ) }
                persistRestorableState(_uiState.value)
            } catch (e: Exception) {
                _uiState.update { it.copy(cashuError = "Melt Error: ${e.message}", cashuLoading = false) }
            }
        }
    }

    fun clearCashuMeltQuote() {
        _uiState.update { it.copy(cashuMeltQuote = null, cashuError = null) }
    }

    fun clearCashuGeneratedToken() {
        _uiState.update { it.copy(cashuGeneratedToken = null) }
    }

    fun clearCashuMeltSuccess() {
        _uiState.update { it.copy(cashuMeltSuccessPreimage = null) }
    }

    fun clearCashuError() {
        _uiState.update { it.copy(cashuError = null) }
    }

    private fun splitAmount(amount: Long): List<Long> {
        val parts = mutableListOf<Long>()
        var temp = amount
        var power = 1L
        while (temp > 0) {
            if ((temp and 1L) != 0L) {
                parts.add(power)
            }
            temp = temp shr 1
            power = power shl 1
        }
        return parts
    }

    private fun serializeProofsJson(proofs: List<CashuProof>): String {
        val array = org.json.JSONArray()
        proofs.forEach { proof ->
            array.put(org.json.JSONObject().apply {
                put("amount", proof.amount)
                put("id", proof.id)
                put("secret", proof.secret)
                put("C", proof.C)
            })
        }
        return array.toString()
    }

    private fun parseProofsJson(jsonStr: String): List<CashuProof> {
        if (jsonStr.isEmpty()) return emptyList()
        return try {
            val array = org.json.JSONArray(jsonStr)
            val list = mutableListOf<CashuProof>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CashuProof(
                        amount = obj.getLong("amount"),
                        id = obj.getString("id"),
                        secret = obj.getString("secret"),
                        C = obj.getString("C")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeCashuTokenV3(mintUrl: String, proofs: List<CashuProof>): String {
        val tokenObj = org.json.JSONObject()
        val tokenArray = org.json.JSONArray()
        val mintEntry = org.json.JSONObject().apply {
            put("mint", mintUrl)
            val proofsArray = org.json.JSONArray()
            proofs.forEach { proof ->
                proofsArray.put(org.json.JSONObject().apply {
                    put("amount", proof.amount)
                    put("id", proof.id)
                    put("secret", proof.secret)
                    put("C", proof.C)
                })
            }
            put("proofs", proofsArray)
        }
        tokenArray.put(mintEntry)
        tokenObj.put("token", tokenArray)
        tokenObj.put("unit", "sat")
        
        val jsonBytes = tokenObj.toString().toByteArray(Charsets.UTF_8)
        val b64 = android.util.Base64.encodeToString(jsonBytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return "cashuA$b64"
    }

    data class ParsedToken(val mint: String, val proofs: List<CashuProof>)

    private fun parseCashuTokenV3(tokenStr: String): ParsedToken {
        val clean = tokenStr.trim()
        require(clean.startsWith("cashuA")) { "Invalid Cashu token format (must start with cashuA)" }
        val b64 = clean.substring(6)
        val jsonBytes = android.util.Base64.decode(b64, android.util.Base64.URL_SAFE)
        val jsonStr = String(jsonBytes, Charsets.UTF_8)
        val json = org.json.JSONObject(jsonStr)
        val tokenArray = json.getJSONArray("token")
        require(tokenArray.length() > 0) { "Token contains no mint details" }
        val firstEntry = tokenArray.getJSONObject(0)
        val mint = firstEntry.getString("mint")
        val proofsArray = firstEntry.getJSONArray("proofs")
        val proofs = mutableListOf<CashuProof>()
        for (i in 0 until proofsArray.length()) {
            val obj = proofsArray.getJSONObject(i)
            proofs.add(
                CashuProof(
                    amount = obj.getLong("amount"),
                    id = obj.getString("id"),
                    secret = obj.getString("secret"),
                    C = obj.getString("C")
                )
            )
        }
        return ParsedToken(mint, proofs)
    }

    // --- End Cashu Wallet Section ---

    private fun restoreUiState(): MainUiState {
        val restoredWriteMessage = savedStateHandle.get<String>(keyWriteMessage).orEmpty()
        val savedMintUrl = savedStateHandle.get<String>(KEY_CASHU_MINT_URL)
        // Migrate users who had the old default 8333.space mint saved
        val restoredMintUrl = when {
            savedMintUrl == null || savedMintUrl == "https://8333.space:5000" -> "https://mint.minibits.cash/Bitcoin"
            else -> savedMintUrl
        }
        val restoredProofsJson = savedStateHandle.get<String>(KEY_CASHU_PROOFS).orEmpty()
        val restoredProofs = parseProofsJson(restoredProofsJson)
        val balance = restoredProofs.sumOf { it.amount }
        val restoredCounter = savedStateHandle.get<Long>(KEY_CASHU_COUNTER) ?: 0L

        val restoredReadMessage = getInMemoryReadMessage()
        val derivedList = restoredReadMessage?.let { deriveBitcoinAddressesAll(it) }
        val derived = restoredReadMessage?.let { deriveBitcoinAddresses(it) }
        val nostrKeys = restoredReadMessage?.let { NostrEngine.deriveNostrKeys(it) }

        return MainUiState(
            selectedScreen = savedStateHandle.get<String>(keySelectedScreen).toAppScreen(),
            writeMessage = restoredWriteMessage,
            writeStatus =
                if (restoredWriteMessage.isBlank()) {
                    defaultWriteStatus()
                } else {
                    StatusMessage("Draft restored. Re-enter the password, then tap Write to Card.")
                },
            estimatedNdefWriteSizeBytes = estimateNdefWriteSize(restoredWriteMessage),
            estimatedMifareClassicWriteSizeBytes = estimateMifareClassicWriteSize(restoredWriteMessage),
            lastTagInfo = restoreLastTagInfo(),
            cashuMintUrl = restoredMintUrl,
            cashuProofs = restoredProofs,
            cashuBalanceSat = balance,
            cashuCounter = restoredCounter,
            readMessage = restoredReadMessage,
            derivedAddresses = derived,
            derivedAddressesList = derivedList,
            nostrNsec = nostrKeys?.nsec,
            nostrNpub = nostrKeys?.npub,
            nostrPubkeyHex = nostrKeys?.pubkeyHex,
            readStatus = if (restoredReadMessage != null) StatusMessage("Wallet decrypted and unlocked.") else defaultReadStatus()
        )
    }

    private fun restoreLastTagInfo(): TagInfo? {
        val backend = savedStateHandle.get<String>(keyLastTagBackend)?.toStorageBackend() ?: return null
        val technologies = savedStateHandle.get<ArrayList<String>>(keyLastTagTechnologies)?.toList().orEmpty()
        return TagInfo(
            capacityBytes = savedStateHandle.get<Int>(keyLastTagCapacityBytes),
            totalCapacityBytes = savedStateHandle.get<Int>(keyLastTagTotalCapacityBytes),
            isWritable = savedStateHandle.get<Boolean>(keyLastTagIsWritable),
            technologies = technologies,
            storageBackend = backend,
            tagDescription = savedStateHandle.get<String>(keyLastTagDescription),
        )
    }

    private fun persistRestorableState(state: MainUiState) {
        savedStateHandle[keySelectedScreen] = state.selectedScreen.name
        if (state.writeMessage.isBlank()) {
            savedStateHandle.remove<String>(keyWriteMessage)
        } else {
            savedStateHandle[keyWriteMessage] = state.writeMessage
        }
        persistLastTagInfo(state.lastTagInfo)
        savedStateHandle[KEY_CASHU_MINT_URL] = state.cashuMintUrl
        savedStateHandle[KEY_CASHU_PROOFS] = serializeProofsJson(state.cashuProofs)
        savedStateHandle[KEY_CASHU_COUNTER] = state.cashuCounter
        setInMemoryReadMessage(state.readMessage)
    }

    private fun persistLastTagInfo(tagInfo: TagInfo?) {
        if (tagInfo == null) {
            savedStateHandle.remove<String>(keyLastTagBackend)
            savedStateHandle.remove<Int>(keyLastTagCapacityBytes)
            savedStateHandle.remove<Int>(keyLastTagTotalCapacityBytes)
            savedStateHandle.remove<Boolean>(keyLastTagIsWritable)
            savedStateHandle.remove<String>(keyLastTagDescription)
            savedStateHandle.remove<ArrayList<String>>(keyLastTagTechnologies)
            return
        }

        savedStateHandle[keyLastTagBackend] = tagInfo.storageBackend.name
        savedStateHandle[keyLastTagCapacityBytes] = tagInfo.capacityBytes
        savedStateHandle[keyLastTagTotalCapacityBytes] = tagInfo.totalCapacityBytes
        savedStateHandle[keyLastTagIsWritable] = tagInfo.isWritable
        savedStateHandle[keyLastTagDescription] = tagInfo.tagDescription
        savedStateHandle[keyLastTagTechnologies] = ArrayList(tagInfo.technologies)
    }

    private fun String?.toAppScreen(): AppScreen =
        runCatching { AppScreen.valueOf(this.orEmpty()) }
            .getOrDefault(AppScreen.Read)

    private fun String.toStorageBackend(): StorageBackend? =
        runCatching { StorageBackend.valueOf(this) }.getOrNull()

    fun setNostrSignerRequest(request: NostrSignerRequest) {
        _uiState.update { it.copy(nostrSignerRequest = request, showSwitchAccount = false, nostrSignerError = null) }
        
        val kind = getRequestEventKind(request)
        val isAutoApproved = when (request.type) {
            "sign_event" -> {
                when (kind) {
                    22242 -> _uiState.value.autoSignKind22242
                    10050 -> _uiState.value.autoSignKind10050
                    31234 -> _uiState.value.autoSignKind31234
                    5 -> _uiState.value.autoSignKind5
                    else -> false
                }
            }
            "nip04_encrypt", "nip44_encrypt" -> _uiState.value.autoSignNipEncrypt
            "nip04_decrypt", "nip44_decrypt" -> _uiState.value.autoSignNipDecrypt
            else -> false
        }
        
        if (isAutoApproved) {
            val mnemonic = _uiState.value.readMessage
            if (mnemonic != null) {
                val keys = NostrEngine.deriveNostrKeys(mnemonic)
                if (keys != null) {
                    val activePubkey = keys.pubkeyHex.trim().lowercase()
                    val expectedPubkey = request.currentUser?.trim()?.lowercase()
                    if (expectedPubkey.isNullOrEmpty() || activePubkey == expectedPubkey) {
                        approveNostrSignerRequest(keys.privkeyHex)
                    }
                }
            }
        }
    }

    fun clearNostrSignerRequest() {
        _uiState.update { it.copy(nostrSignerRequest = null, showSwitchAccount = false, nostrSignerError = null) }
    }

    fun rejectNostrSignerRequest() {
        val request = _uiState.value.nostrSignerRequest ?: return
        viewModelScope.launch {
            _nostrSignerResults.send(NostrSignerResultEvent.Rejected(request.id))
            clearNostrSignerRequest()
        }
    }

    fun dismissNostrSignerErrorAndReject() {
        _uiState.update { it.copy(nostrSignerError = null) }
        rejectNostrSignerRequest()
    }

    fun approveNostrSignerRequestWithCurrentWallet() {
        val mnemonic = _uiState.value.readMessage ?: return
        val keys = NostrEngine.deriveNostrKeys(mnemonic) ?: return
        approveNostrSignerRequest(keys.privkeyHex)
    }

    fun approveNostrSignerRequest(privateKeyHex: String) {
        val request = _uiState.value.nostrSignerRequest ?: return
        viewModelScope.launch {
            try {
                val activeKeys = NostrEngine.deriveNostrKeys(privateKeyHex) ?: throw IllegalArgumentException("Invalid key derivation")
                val activePubkey = activeKeys.pubkeyHex.trim().lowercase()
                val expectedPubkey = request.currentUser?.trim()?.lowercase()
                if (!expectedPubkey.isNullOrEmpty() && activePubkey != expectedPubkey) {
                    throw SecurityException("Active signer public key ($activePubkey) does not match expected public key ($expectedPubkey)")
                }

                val resultString: String
                var signedEventJson: String? = null

                when (request.type) {
                    "get_public_key" -> {
                        val keys = NostrEngine.deriveNostrKeys(privateKeyHex)
                        resultString = keys?.pubkeyHex ?: ""
                    }
                    "sign_event" -> {
                        val eventJson = request.eventJson ?: throw IllegalArgumentException("Missing event JSON")
                        val (sigHex, signedJson) = NostrEngine.signEvent(eventJson, privateKeyHex)
                        resultString = sigHex
                        signedEventJson = signedJson
                    }
                    "nip04_encrypt" -> {
                        val plaintext = request.plaintext ?: throw IllegalArgumentException("Missing plaintext")
                        val destPubkey = request.destPubkey ?: throw IllegalArgumentException("Missing destination public key")
                        resultString = NostrEngine.nip04Encrypt(plaintext, destPubkey, privateKeyHex)
                    }
                    "nip04_decrypt" -> {
                        val ciphertext = request.ciphertext ?: throw IllegalArgumentException("Missing ciphertext")
                        val destPubkey = request.destPubkey ?: throw IllegalArgumentException("Missing destination public key")
                        resultString = NostrEngine.nip04Decrypt(ciphertext, destPubkey, privateKeyHex, request.iv)
                    }
                    "nip44_encrypt" -> {
                        val plaintext = request.plaintext ?: throw IllegalArgumentException("Missing plaintext")
                        val destPubkey = request.destPubkey ?: throw IllegalArgumentException("Missing destination public key")
                        resultString = NostrEngine.nip44Encrypt(plaintext, destPubkey, privateKeyHex)
                    }
                    "nip44_decrypt" -> {
                        val ciphertext = request.ciphertext ?: throw IllegalArgumentException("Missing ciphertext")
                        val destPubkey = request.destPubkey ?: throw IllegalArgumentException("Missing destination public key")
                        resultString = NostrEngine.nip44Decrypt(ciphertext, destPubkey, privateKeyHex)
                    }
                    "decrypt_zap_event" -> {
                        // NIP-57: parse the zap receipt, find the "description" tag which
                        // contains the original zap request JSON, then NIP-04 decrypt its
                        // content if non-empty (anonymous zap). Return the decrypted content
                        // or the description tag value as-is for non-anonymous zaps.
                        val eventStr = request.eventJson
                            ?: request.ciphertext
                            ?: throw IllegalArgumentException("Missing zap event JSON")
                        val eventObj = org.json.JSONObject(eventStr)
                        val tags = eventObj.optJSONArray("tags")
                        var description: String? = null
                        if (tags != null) {
                            for (i in 0 until tags.length()) {
                                val tag = tags.optJSONArray(i)
                                if (tag != null && tag.optString(0) == "description") {
                                    description = tag.optString(1)
                                    break
                                }
                            }
                        }
                        if (description != null) {
                            val zapRequest = runCatching { org.json.JSONObject(description) }.getOrNull()
                            val zapContent = zapRequest?.optString("content", "") ?: ""
                            val zapperPubkey = zapRequest?.optString("pubkey", "") ?: ""
                            resultString = if (zapContent.isNotEmpty() && zapperPubkey.isNotEmpty()) {
                                // Anonymous zap — decrypt the NIP-04 content
                                NostrEngine.nip04Decrypt(zapContent, zapperPubkey, privateKeyHex)
                            } else {
                                // Non-anonymous zap — return the zap request JSON as-is
                                description
                            }
                        } else {
                            // No description tag — fall back to decrypting event content directly
                            val content = eventObj.optString("content", "")
                            val pubkey = eventObj.optString("pubkey", "")
                            resultString = if (content.isNotEmpty() && pubkey.isNotEmpty()) {
                                NostrEngine.nip04Decrypt(content, pubkey, privateKeyHex)
                            } else {
                                eventStr
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown request type: ${request.type}")
                }


                _nostrSignerResults.send(
                    NostrSignerResultEvent.Success(
                        type = request.type,
                        id = request.id,
                        result = resultString,
                        event = signedEventJson
                    )
                )
                clearNostrSignerRequest()
            } catch (e: Exception) {
                _feedbackEvents.emit(UserFeedbackEvent("Nostr operation failed: ${e.message}", FeedbackTone.Error))
                if (e is SecurityException) {
                    _uiState.update { it.copy(showSwitchAccount = true) }
                } else {
                    _uiState.update { it.copy(nostrSignerError = e.message ?: "Unknown error occurred") }
                }
            }
        }
    }

    companion object {
        const val readPasswordAttemptDelayMs = 300L
        const val keySelectedScreen = "selected_screen"
        const val keyWriteMessage = "write_message"
        const val keyLastTagBackend = "last_tag_backend"
        const val keyLastTagCapacityBytes = "last_tag_capacity_bytes"
        const val keyLastTagTotalCapacityBytes = "last_tag_total_capacity_bytes"
        const val keyLastTagIsWritable = "last_tag_is_writable"
        const val keyLastTagDescription = "last_tag_description"
        const val keyLastTagTechnologies = "last_tag_technologies"
        const val KEY_CASHU_MINT_URL = "cashu_mint_url"
        const val KEY_CASHU_PROOFS = "cashu_proofs_json"
        const val KEY_CASHU_COUNTER = "cashu_counter"

        @Volatile
        private var inMemoryReadMessage: String? = null

        fun getInMemoryReadMessage(): String? = inMemoryReadMessage
        fun setInMemoryReadMessage(message: String?) {
            inMemoryReadMessage = message
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun deriveCashuMasterSeed(): ByteArray? {
        val mnemonic = _uiState.value.readMessage ?: return null
        val clean = mnemonic.trim()
        val words = clean.lowercase().split(Regex("\\s+"))
        if (words.size == 12 || words.size == 24) {
            return try {
                MnemonicCode.toSeed(words, "")
            } catch (e: Exception) {
                null
            }
        }
        val nsecPrivKey = KeyParser.parseNsec(clean)
        if (nsecPrivKey != null) {
            return nsecPrivKey.value.toByteArray()
        }
        val parsedPrivKey = KeyParser.parsePrivateKey(clean)
        if (parsedPrivKey != null) {
            return parsedPrivKey.value.toByteArray()
        }
        return null
    }

    private fun defaultReadStatus(): StatusMessage =
        StatusMessage("Hold a compatible NFC tag against the phone. If a message is found, enter the password and tap Try Password to decrypt it.")

    private fun defaultWriteStatus(): StatusMessage =
        StatusMessage("Enter a password and message, then tap Write to Card.")

    private fun disabledNfcStatus(isAvailable: Boolean): StatusMessage =
        if (isAvailable) {
            StatusMessage("NFC is turned off. Turn it on in Android settings to scan cards.", true)
        } else {
            StatusMessage("This device does not expose an NFC adapter.", true)
        }
}
