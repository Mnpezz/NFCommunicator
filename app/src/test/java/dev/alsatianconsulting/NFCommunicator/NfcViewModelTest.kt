package dev.alsatianconsulting.NFCommunicator

import androidx.lifecycle.SavedStateHandle
import dev.alsatianconsulting.NFCommunicator.domain.Bip39Compressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the NfcViewModel state-machine paths that do not require NFC hardware.
 *
 * These tests exercise validation logic (password length, confirmation match, blank fields) and
 * state transitions (pending scan, cancel) using only the synchronous parts of the ViewModel.
 * Coroutine-heavy paths (handleTag, handlePassiveReadTag) require a hardware NFC tag and are
 * covered by the on-device regression described in testing-and-next-steps.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NfcViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        // Replace the Main dispatcher so viewModelScope.launch does not fail on JVM without Android.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun viewModel(): NfcViewModel =
        NfcViewModel(SavedStateHandle(), testDispatcher).also { vm ->
            vm.setNfcState(isAvailable = true, isEnabled = true)
        }

    // ── Wizard Write validation ─────────────────────────────────────────────

    @Test
    fun startWriteWizard_blankMessage_setsWriteStatusError() {
        val vm = viewModel()
        vm.startWriteWizard()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
        assertFalse(vm.uiState.value.writeWizardActive)
    }

    @Test
    fun startWriteWizard_validMessage_activatesWizard() {
        val vm = viewModel()
        vm.updateWriteMessage("hello world")
        vm.startWriteWizard()

        assertFalse(vm.uiState.value.writeStatus.isError)
        assertTrue(vm.uiState.value.writeWizardActive)
        assertEquals(1, vm.uiState.value.writeWizardRawShares.size)
        assertEquals(0, vm.uiState.value.writeWizardIndex)
    }

    @Test
    fun proceedWithWizardWrite_blankPassword_setsWriteStatusError() {
        val vm = viewModel()
        vm.updateWriteMessage("hello")
        vm.startWriteWizard()
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_shortPassword_setsWriteStatusError() {
        val vm = viewModel()
        vm.updateWriteMessage("hello")
        vm.startWriteWizard()
        val shortPassword = "x".repeat(MIN_PASSWORD_LENGTH - 1)
        vm.updateWriteWizardPassword(shortPassword)
        vm.updateWriteWizardPasswordConfirmation(shortPassword)
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertTrue(vm.uiState.value.writeStatus.text.contains(MIN_PASSWORD_LENGTH.toString()))
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_mismatchedPasswords_setsWriteStatusError() {
        val vm = viewModel()
        vm.updateWriteMessage("hello")
        vm.startWriteWizard()
        vm.updateWriteWizardPassword("password-one-long")
        vm.updateWriteWizardPasswordConfirmation("password-two-long")
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_validInputs_setsPendingWriteAction() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWriteMessage("hello world")
        vm.startWriteWizard()
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        vm.proceedWithWizardWrite()

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.writeStatus.isError)
        assertTrue(vm.uiState.value.pendingScanAction is PendingScanAction.Write)
    }

    // ── beginReadScan validation ──────────────────────────────────────────────

    @Test
    fun beginReadScan_blankPassword_setsReadStatusError() {
        val vm = viewModel()
        vm.beginReadScan()

        assertTrue(vm.uiState.value.readStatus.isError)
    }

    @Test
    fun beginReadScan_shortPassword_setsReadStatusError() {
        val vm = viewModel()
        vm.updateReadPassword("x".repeat(MIN_PASSWORD_LENGTH - 1))
        vm.beginReadScan()

        assertTrue(vm.uiState.value.readStatus.isError)
        assertTrue(
            vm.uiState.value.readStatus.text.contains(MIN_PASSWORD_LENGTH.toString()),
        )
    }

    // ── beginClearScan ────────────────────────────────────────────────────────

    @Test
    fun beginClearScan_setsReadTabPendingClearAction() {
        val vm = viewModel()
        vm.setSelectedScreen(AppScreen.Read)
        vm.beginClearScan()

        val action = vm.uiState.value.pendingScanAction
        assertTrue(action is PendingScanAction.Clear)
        assertTrue((action as PendingScanAction.Clear).origin == AppScreen.Read)
    }

    @Test
    fun beginClearScan_setsWriteTabPendingClearAction() {
        val vm = viewModel()
        vm.setSelectedScreen(AppScreen.Write)
        vm.beginClearScan()

        val action = vm.uiState.value.pendingScanAction
        assertTrue(action is PendingScanAction.Clear)
        assertTrue((action as PendingScanAction.Clear).origin == AppScreen.Write)
    }

    // ── cancelPendingScan ─────────────────────────────────────────────────────

    @Test
    fun cancelPendingScan_clearsPendingAction() {
        val vm = viewModel()
        vm.beginClearScan()
        assertTrue(vm.uiState.value.pendingScanAction != null)

        vm.cancelPendingScan()

        assertNull(vm.uiState.value.pendingScanAction)
    }

    // ── password clearing on screen switch ────────────────────────────────────

    @Test
    fun updateWriteMessage_updatesEstimatedSizes() {
        val vm = viewModel()
        vm.updateWriteMessage("test message")

        assertTrue(vm.uiState.value.estimatedNdefWriteSizeBytes > 0)
        assertTrue(vm.uiState.value.estimatedMifareClassicWriteSizeBytes > 0)
    }

    @Test
    fun updateWriteMessage_blank_resetsEstimatedSizesToZero() {
        val vm = viewModel()
        vm.updateWriteMessage("something")
        vm.updateWriteMessage("")

        assertTrue(vm.uiState.value.estimatedNdefWriteSizeBytes == 0)
        assertTrue(vm.uiState.value.estimatedMifareClassicWriteSizeBytes == 0)
    }

    @Test
    fun updateWriteMessage_valid12WordMnemonic_correctlyEstimatesCompressedSize() {
        val vm = viewModel()
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        vm.updateWriteMessage(mnemonic)

        val estimatedNdef = vm.uiState.value.estimatedNdefWriteSizeBytes
        val estimatedMifare = vm.uiState.value.estimatedMifareClassicWriteSizeBytes

        // 12 words = 16 bytes entropy. Encrypted payload is 45 + 16 = 61 bytes.
        // NDEF overhead with "app/nc" MIME is 9 bytes -> 70 bytes.
        // Mifare classic header is 12 bytes -> 73 bytes.
        assertEquals(70, estimatedNdef)
        assertEquals(73, estimatedMifare)
    }

    @Test
    fun updateWriteMessage_valid24WordMnemonic_correctlyEstimatesCompressedSize() {
        val vm = viewModel()
        val words = Bip39Compressor.generateMnemonic(24)
        val mnemonic = words.joinToString(" ")
        vm.updateWriteMessage(mnemonic)

        val estimatedNdef = vm.uiState.value.estimatedNdefWriteSizeBytes
        val estimatedMifare = vm.uiState.value.estimatedMifareClassicWriteSizeBytes

        // 24 words = 32 bytes entropy. Encrypted payload is 45 + 32 = 77 bytes.
        // NDEF overhead is 9 bytes -> 86 bytes.
        // Mifare classic is 77 + 12 = 89 bytes.
        assertEquals(86, estimatedNdef)
        assertEquals(89, estimatedMifare)
    }

    @Test
    fun startWriteWizard_withMultiNfcSplit_setsWizardShares() {
        val vm = viewModel()
        vm.updateWriteMessage("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        vm.updateWriteIsMultiNfcSplit(true)
        vm.startWriteWizard()

        assertFalse(vm.uiState.value.writeStatus.isError)
        assertTrue(vm.uiState.value.writeWizardActive)
        assertEquals(3, vm.uiState.value.writeWizardRawShares.size)
        assertEquals(0, vm.uiState.value.writeWizardIndex)
        assertEquals(2, vm.uiState.value.writeWizardK)
    }

    @Test
    fun proceedWithWizardWrite_withMultiNfcSplitAndDuress_precomputesCorrectPayload() {
        val vm = viewModel()
        vm.updateWriteMessage("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        vm.updateWriteIsMultiNfcSplit(true)
        vm.updateWriteIsDuressEnabled(true)
        vm.startWriteWizard()

        val pw = "validpassword"
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        vm.updateWriteWizardSecondaryMnemonic("emergency seed")
        vm.updateWriteWizardSecondaryPassword("emergency-pw")
        vm.updateWriteWizardSecondaryPasswordConfirmation("emergency-pw")
        vm.proceedWithWizardWrite()

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.writeStatus.isError)
        val action = vm.uiState.value.pendingScanAction
        assertTrue(action is PendingScanAction.Write)
        val writeAction = action as PendingScanAction.Write
        val payload = writeAction.encryptedPayload

        // Verify that the secondary wallet password decrypts to the secondary wallet mnemonic
        val decryptedEmergency = dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec.decryptPayload(
            payload, "emergency-pw"
        )
        assertEquals("emergency seed", decryptedEmergency)

        // Verify that the vault password extracts the SSS share bytes correctly
        val decryptedShare = dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec.decryptSharePayload(
            payload, pw
        )
        assertTrue(decryptedShare.isNotEmpty())
    }

    @Test
    fun updateWriteMultiNfcParameters_enforcesConstraints() {
        val vm = viewModel()
        assertEquals(3, vm.uiState.value.writeMultiNfcN)
        assertEquals(2, vm.uiState.value.writeMultiNfcK)

        vm.updateWriteMultiNfcN(5)
        assertEquals(5, vm.uiState.value.writeMultiNfcN)
        assertEquals(2, vm.uiState.value.writeMultiNfcK) // K stays the same if within bounds

        vm.updateWriteMultiNfcK(4)
        assertEquals(4, vm.uiState.value.writeMultiNfcK)

        vm.updateWriteMultiNfcN(3)
        assertEquals(3, vm.uiState.value.writeMultiNfcN)
        assertEquals(3, vm.uiState.value.writeMultiNfcK) // K is coerced down to N
    }

    @Test
    fun beginMultiNfcUnlock_validInputs_setsPendingReadShareAction() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateReadPassword(pw)
        vm.beginMultiNfcUnlock()

        assertFalse(vm.uiState.value.readStatus.isError)
        val action = vm.uiState.value.pendingScanAction
        assertTrue(action is PendingScanAction.ReadShare)
        val readShare = action as PendingScanAction.ReadShare
        assertTrue(readShare.gathered.isEmpty())
        assertNull(readShare.thresholdK)
        assertTrue(vm.uiState.value.isMultiNfcUnlock)
    }

    // ── Breez Integration tests ──────────────────────────────────────────────

    @Test
    fun breezApiKeyAndNetworkUpdates() {
        val vm = viewModel()
        vm.onBreezApiKeyChanged("test-api-key")
        assertEquals("test-api-key", vm.uiState.value.breezApiKey)

        vm.onBreezNetworkChanged("MAINNET")
        assertEquals("MAINNET", vm.uiState.value.breezNetwork)
    }

    @Test
    fun breezPaymentStateFieldsUpdates() {
        val vm = viewModel()
        vm.onBreezReceiveAmountChanged("1000")
        assertEquals("1000", vm.uiState.value.breezReceiveAmount)

        vm.onBreezSendDestinationChanged("invoice-destination")
        assertEquals("invoice-destination", vm.uiState.value.breezSendDestination)

        vm.onBreezSendAmountChanged("2000")
        assertEquals("2000", vm.uiState.value.breezSendAmount)
    }

    @Test
    fun onBreezDisconnect_resetsBreezFields() {
        val vm = viewModel()
        vm.onBreezApiKeyChanged("test-api-key")
        vm.onBreezReceiveAmountChanged("1000")
        vm.onBreezSendDestinationChanged("invoice-destination")
        
        vm.onBreezDisconnect()
        
        assertEquals("", vm.uiState.value.breezReceiveAmount)
        assertEquals("", vm.uiState.value.breezSendDestination)
        assertFalse(vm.uiState.value.breezConnected)
    }

    @Test
    fun closeWallet_disconnectsAndResetsBreezFields() {
        val vm = viewModel()
        vm.onBreezApiKeyChanged("test-api-key")
        vm.onBreezReceiveAmountChanged("1000")
        
        vm.closeWallet()
        
        assertEquals("", vm.uiState.value.breezReceiveAmount)
        assertFalse(vm.uiState.value.breezConnected)
    }

    @Test
    fun onBreezGenerateInvoice_withBlankAmount_setsError() {
        val vm = viewModel()
        vm.onBreezReceiveAmountChanged("")
        vm.onBreezGenerateInvoice()

        assertTrue(vm.uiState.value.breezError?.contains("amount") == true)
    }

    @Test
    fun onBreezPrepareSend_withBlankDestination_setsError() {
        val vm = viewModel()
        vm.onBreezSendDestinationChanged("")
        vm.onBreezPrepareSend()

        assertTrue(vm.uiState.value.breezError?.contains("Destination") == true)
    }

    @Test
    fun proceedWithWizardWrite_duressEnabled_blankEmergencyPassword_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.startWriteWizard()
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        vm.updateWriteWizardSecondaryMnemonic("emergency-hello")
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_duressEnabled_shortEmergencyPassword_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.startWriteWizard()
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        val shortEmergencyPassword = "x".repeat(MIN_PASSWORD_LENGTH - 1)
        vm.updateWriteWizardSecondaryPassword(shortEmergencyPassword)
        vm.updateWriteWizardSecondaryPasswordConfirmation(shortEmergencyPassword)
        vm.updateWriteWizardSecondaryMnemonic("emergency-hello")
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_duressEnabled_mismatchedEmergencyPasswords_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.startWriteWizard()
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        vm.updateWriteWizardSecondaryPassword("emergency1")
        vm.updateWriteWizardSecondaryPasswordConfirmation("emergency2")
        vm.updateWriteWizardSecondaryMnemonic("emergency-hello")
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_duressEnabled_sameMainAndEmergencyPassword_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.startWriteWizard()
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        vm.updateWriteWizardSecondaryPassword(pw)
        vm.updateWriteWizardSecondaryPasswordConfirmation(pw)
        vm.updateWriteWizardSecondaryMnemonic("emergency-hello")
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_duressEnabled_blankEmergencyMessage_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.startWriteWizard()
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        vm.updateWriteWizardSecondaryPassword("emergency-pw")
        vm.updateWriteWizardSecondaryPasswordConfirmation("emergency-pw")
        vm.proceedWithWizardWrite()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun proceedWithWizardWrite_duressEnabled_validInputs_setsPendingWriteActionWithDuress() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWriteMessage("hello world")
        vm.updateWriteIsDuressEnabled(true)
        vm.startWriteWizard()
        vm.updateWriteWizardPassword(pw)
        vm.updateWriteWizardPasswordConfirmation(pw)
        vm.updateWriteWizardSecondaryPassword("emergency-pw")
        vm.updateWriteWizardSecondaryPasswordConfirmation("emergency-pw")
        vm.updateWriteWizardSecondaryMnemonic("emergency seed")
        vm.proceedWithWizardWrite()

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.writeStatus.isError)
        val action = vm.uiState.value.pendingScanAction
        assertTrue(action is PendingScanAction.Write)
        val writeAction = action as PendingScanAction.Write

        // Verify decryption of pre-encrypted payload
        val decryptedMain = dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec.decryptPayload(
            writeAction.encryptedPayload, pw
        )
        assertEquals("hello world", decryptedMain)

        val decryptedEmergency = dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec.decryptPayload(
            writeAction.encryptedPayload, "emergency-pw"
        )
        assertEquals("emergency seed", decryptedEmergency)
    }

    // ── Nostr Signer tests ────────────────────────────────────────────────────

    @Test
    fun setNostrSignerRequest_updatesUiState() {
        val vm = viewModel()
        val request = NostrSignerRequest(
            type = "get_public_key",
            id = "123",
            eventJson = null,
            plaintext = null,
            ciphertext = null,
            destPubkey = null,
            callingPackage = "test.client"
        )
        vm.setNostrSignerRequest(request)
        assertEquals(request, vm.uiState.value.nostrSignerRequest)
    }

    @Test
    fun rejectNostrSignerRequest_clearsRequestAndEmitsRejected() {
        val vm = viewModel()
        val request = NostrSignerRequest(
            type = "get_public_key",
            id = "123",
            eventJson = null,
            plaintext = null,
            ciphertext = null,
            destPubkey = null,
            callingPackage = "test.client"
        )
        vm.setNostrSignerRequest(request)

        var emittedResult: NostrSignerResultEvent? = null
        val job = kotlinx.coroutines.MainScope().launch {
            vm.nostrSignerResults.collect {
                emittedResult = it
            }
        }

        vm.rejectNostrSignerRequest()

        assertNull(vm.uiState.value.nostrSignerRequest)
        assertTrue(emittedResult is NostrSignerResultEvent.Rejected)
        assertEquals("123", (emittedResult as NostrSignerResultEvent.Rejected).id)

        job.cancel()
    }

    @Test
    fun approveNostrSignerRequest_withPublicKeyRequest_emitsSuccess() {
        val vm = viewModel()
        val request = NostrSignerRequest(
            type = "get_public_key",
            id = "123",
            eventJson = null,
            plaintext = null,
            ciphertext = null,
            destPubkey = null,
            callingPackage = "test.client"
        )
        vm.setNostrSignerRequest(request)

        var emittedResult: NostrSignerResultEvent? = null
        val job = kotlinx.coroutines.MainScope().launch {
            vm.nostrSignerResults.collect {
                emittedResult = it
            }
        }

        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val expectedKeys = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.deriveNostrKeys(mnemonic)!!
        vm.approveNostrSignerRequest(mnemonic)

        assertNull(vm.uiState.value.nostrSignerRequest)
        assertTrue(emittedResult is NostrSignerResultEvent.Success)
        val success = emittedResult as NostrSignerResultEvent.Success
        assertEquals("123", success.id)
        assertEquals(expectedKeys.pubkeyHex, success.result)

        job.cancel()
    }

    @Test
    fun closeWallet_clearsCashuProofsAndQuotes() {
        val vm = viewModel()
        val field = NfcViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<MainUiState>
        stateFlow.value = stateFlow.value.copy(
            cashuProofs = listOf(dev.alsatianconsulting.NFCommunicator.domain.CashuProof(100L, "id", "secret", "C")),
            cashuBalanceSat = 100L,
            cashuMintQuoteAmountSat = 50L
        )
        assertEquals(100L, vm.uiState.value.cashuBalanceSat)
        assertEquals(1, vm.uiState.value.cashuProofs.size)

        vm.closeWallet()

        assertEquals(0L, vm.uiState.value.cashuBalanceSat)
        assertTrue(vm.uiState.value.cashuProofs.isEmpty())
        assertEquals(0L, vm.uiState.value.cashuMintQuoteAmountSat)
    }

    @Test
    fun approveNostrSignerRequest_withMismatchedExpectedPubKey_failsAndEnablesSwitchAccount() {
        val vm = viewModel()
        val request = NostrSignerRequest(
            type = "sign_event",
            id = "123",
            eventJson = "{\"pubkey\":\"different_pubkey\"}",
            plaintext = null,
            ciphertext = null,
            destPubkey = null,
            callingPackage = "test.client",
            currentUser = "different_pubkey"
        )
        vm.setNostrSignerRequest(request)
        assertFalse(vm.uiState.value.showSwitchAccount)

        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        vm.approveNostrSignerRequest(mnemonic)

        // Request shouldn't be cleared, and showSwitchAccount should be true
        assertEquals(request, vm.uiState.value.nostrSignerRequest)
        assertTrue(vm.uiState.value.showSwitchAccount)
    }

    @Test
    fun beginMultiNfcUnlock_initializesReadWizardCorrectly() {
        val vm = viewModel()
        vm.beginMultiNfcUnlock()

        assertTrue(vm.uiState.value.readWizardActive)
        assertEquals(0, vm.uiState.value.readWizardIndex)
        assertEquals(2, vm.uiState.value.readWizardK)
        assertTrue(vm.uiState.value.readWizardPayloads.isEmpty())
        assertTrue(vm.uiState.value.readWizardDecryptedShares.isEmpty())
        assertEquals("", vm.uiState.value.readWizardPasswordInput)
        assertTrue(vm.uiState.value.isMultiNfcUnlock)
        assertTrue(vm.uiState.value.pendingScanAction is PendingScanAction.ReadShare)
    }

    @Test
    fun cancelReadWizard_resetsWizardState() {
        val vm = viewModel()
        vm.beginMultiNfcUnlock()
        vm.updateReadWizardPasswordInput("some-password")
        vm.cancelReadWizard()

        assertFalse(vm.uiState.value.readWizardActive)
        assertEquals("", vm.uiState.value.readWizardPasswordInput)
        assertFalse(vm.uiState.value.isMultiNfcUnlock)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun decryptWizardShare_withShortPassword_setsStatusError() {
        val vm = viewModel()
        vm.beginMultiNfcUnlock()
        vm.updateReadWizardPasswordInput("short")
        vm.decryptWizardShare()

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.readStatus.isError)
        assertTrue(vm.uiState.value.readStatus.text.contains(MIN_PASSWORD_LENGTH.toString()))
        assertFalse(vm.uiState.value.readWizardIsProcessing)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun decryptWizardShare_withDuplicateShare_setsStatusError() {
        val vm = viewModel()

        // Setup SSS
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        vm.updateWriteMessage(mnemonic)
        vm.updateWriteIsMultiNfcSplit(true)
        vm.startWriteWizard()

        val rawShares = vm.uiState.value.writeWizardRawShares
        val payloadCard1 = dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec.encryptShareToPayload(rawShares[0], "password-one")

        vm.beginMultiNfcUnlock()

        // Use reflection to update the private _uiState flow
        val field = NfcViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val uiStateFlow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<MainUiState>
        uiStateFlow.value = uiStateFlow.value.copy(readWizardPayloads = listOf(payloadCard1, payloadCard1), pendingScanAction = null)

        // Decrypt card 1 first time
        vm.updateReadWizardPasswordInput("password-one")
        vm.decryptWizardShare()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.readStatus.isError)
        assertEquals(1, vm.uiState.value.readWizardDecryptedShares.size)
        assertEquals(1, vm.uiState.value.readWizardIndex)

        // Try decrypting card 1 second time
        vm.updateReadWizardPasswordInput("password-one")
        vm.decryptWizardShare()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.readStatus.isError)
        assertTrue(vm.uiState.value.readStatus.text.contains("already been successfully decrypted"))
        assertEquals(1, vm.uiState.value.readWizardDecryptedShares.size)
    }
}
