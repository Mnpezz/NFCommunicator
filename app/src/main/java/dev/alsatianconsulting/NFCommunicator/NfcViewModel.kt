package dev.alsatianconsulting.NFCommunicator

import android.nfc.Tag
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.alsatianconsulting.NFCommunicator.domain.NfcOperationResult
import dev.alsatianconsulting.NFCommunicator.domain.NfcPayloadReadResult
import dev.alsatianconsulting.NFCommunicator.domain.NfcTagService
import dev.alsatianconsulting.NFCommunicator.domain.NfcDiagnostics
import dev.alsatianconsulting.NFCommunicator.domain.InvalidPasswordException
import dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec
import dev.alsatianconsulting.NFCommunicator.domain.StorageBackend
import dev.alsatianconsulting.NFCommunicator.domain.TagInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

sealed interface PendingScanAction {
    data class Read(val password: String) : PendingScanAction
    data class Write(val password: String, val message: String) : PendingScanAction
    data class Clear(val origin: AppScreen) : PendingScanAction
}

data class MainUiState(
    val nfcAvailable: Boolean = true,
    val nfcEnabled: Boolean = true,
    val selectedScreen: AppScreen = AppScreen.Read,
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

            else -> null
        }
}

class NfcViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val tagService = NfcTagService()
    private val _uiState = MutableStateFlow(restoreUiState())
    private val _feedbackEvents = MutableSharedFlow<UserFeedbackEvent>(extraBufferCapacity = 4)
    private var cachedReadPayload: ByteArray? = null
    private var readPasswordAttemptJob: Job? = null
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val feedbackEvents = _feedbackEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            uiState.collect(::persistRestorableState)
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
            )
        }
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
            cachedReadPayload == null -> {
                updateReadStatus("Scan a compatible tag first. Cards are detected automatically on this screen.", isError = true)
            }

            state.readPassword.isBlank() -> {
                updateReadStatus("Enter the shared password to try decrypting the detected tag.", isError = true)
            }

            state.readPassword.length < MIN_PASSWORD_LENGTH -> {
                updateReadStatus(
                    "The password must be at least $MIN_PASSWORD_LENGTH characters.",
                    isError = true,
                )
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

            else -> {
                _uiState.update {
                    it.copy(
                        pendingScanAction = PendingScanAction.Write(state.writePassword, state.writeMessage),
                        writeStatus = StatusMessage("Ready to scan a tag for writing."),
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

    fun noteDetectedTag(tag: Tag) {
        val state = _uiState.value
        if (state.pendingScanAction != null || state.isProcessing) {
            return
        }

        val tagInfo = tagService.inspectTag(tag)
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

    fun handlePassiveReadTag(tag: Tag) {
        val state = _uiState.value
        if (state.pendingScanAction != null || state.isProcessing) {
            return
        }

        _uiState.update {
            it.copy(
                isProcessing = true,
                readMessage = null,
                readStatus = StatusMessage("Reading the detected tag..."),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result =
                try {
                    tagService.readEncryptedPayload(tag)
                } catch (error: Throwable) {
                    unexpectedPayloadFailureResult(tag, error)
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
                            },
                )
            }
            scheduleCachedReadDecryption(_uiState.value.readPassword, immediate = true)
        }
    }

    fun handleTag(tag: Tag) {
        val action = _uiState.value.pendingScanAction ?: return

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
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result =
                try {
                    when (action) {
                        is PendingScanAction.Read -> tagService.readEncryptedMessage(tag, action.password)
                        is PendingScanAction.Write -> tagService.writeEncryptedMessage(tag, action.password, action.message)
                        is PendingScanAction.Clear -> tagService.clearTag(tag)
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
                        nextState.copy(
                            readPassword = "",
                            readMessage = result.decryptedMessage,
                            readStatus = result.asStatusMessage(),
                        )
                    }

                    is PendingScanAction.Write -> {
                        nextState.copy(
                            writePassword = "",
                            writePasswordConfirmation = "",
                            writeStatus = result.asStatusMessage(),
                        )
                    }

                    is PendingScanAction.Clear -> {
                        if (action.origin == AppScreen.Read) {
                            nextState.copy(
                                readPassword = "",
                                readMessage = null,
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
                }
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

            _uiState.update { state ->
                state.copy(
                    isProcessing = false,
                    readMessage = result.decryptedMessage,
                    readStatus = result.asStatusMessage(),
                    lastTagInfo = result.tagInfo,
                )
            }
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
        }
        return UserFeedbackEvent(message, FeedbackTone.Success)
    }

    private val PendingScanAction.description: String
        get() = when (this) {
            is PendingScanAction.Read -> "read"
            is PendingScanAction.Write -> "write"
            is PendingScanAction.Clear -> "clear"
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

    private fun restoreUiState(): MainUiState {
        val restoredWriteMessage = savedStateHandle.get<String>(keyWriteMessage).orEmpty()
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

    private companion object {
        const val readPasswordAttemptDelayMs = 300L
        const val keySelectedScreen = "selected_screen"
        const val keyWriteMessage = "write_message"
        const val keyLastTagBackend = "last_tag_backend"
        const val keyLastTagCapacityBytes = "last_tag_capacity_bytes"
        const val keyLastTagTotalCapacityBytes = "last_tag_total_capacity_bytes"
        const val keyLastTagIsWritable = "last_tag_is_writable"
        const val keyLastTagDescription = "last_tag_description"
        const val keyLastTagTechnologies = "last_tag_technologies"
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
