package dev.alsatianconsulting.NFCommunicator

import androidx.lifecycle.SavedStateHandle
import dev.alsatianconsulting.NFCommunicator.domain.Bip39Compressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        Dispatchers.resetMain()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun viewModel(): NfcViewModel =
        NfcViewModel(SavedStateHandle()).also { vm ->
            vm.setNfcState(isAvailable = true, isEnabled = true)
        }

    // ── beginWriteScan validation ─────────────────────────────────────────────

    @Test
    fun beginWriteScan_blankPassword_setsWriteStatusError() {
        val vm = viewModel()
        vm.updateWriteMessage("hello")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_shortPassword_setsWriteStatusError() {
        val vm = viewModel()
        val shortPassword = "x".repeat(MIN_PASSWORD_LENGTH - 1)
        vm.updateWritePassword(shortPassword)
        vm.updateWritePasswordConfirmation(shortPassword)
        vm.updateWriteMessage("hello")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertTrue(
            vm.uiState.value.writeStatus.text.contains(MIN_PASSWORD_LENGTH.toString()),
        )
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_mismatchedPasswords_setsWriteStatusError() {
        val vm = viewModel()
        vm.updateWritePassword("password-one-long")
        vm.updateWritePasswordConfirmation("password-two-long")
        vm.updateWriteMessage("hello")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_blankMessage_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_validInputs_setsPendingWriteAction() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("hello world")
        vm.beginWriteScan()

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
    fun beginWriteScan_withMultiNfcSplit_setsPendingWriteShareAction() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        vm.updateWriteIsMultiNfcSplit(true)
        vm.beginWriteScan()

        assertFalse(vm.uiState.value.writeStatus.isError)
        val action = vm.uiState.value.pendingScanAction
        assertTrue(action is PendingScanAction.WriteShare)
        val writeShare = action as PendingScanAction.WriteShare
        assertEquals(3, writeShare.shares.size)
        assertEquals(0, writeShare.currentIndex)
        assertEquals(2, writeShare.thresholdK)
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
    fun beginWriteScan_duressEnabled_blankEmergencyPassword_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.updateWriteEmergencyMessage("emergency-hello")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_duressEnabled_shortEmergencyPassword_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        val shortEmergencyPassword = "x".repeat(MIN_PASSWORD_LENGTH - 1)
        vm.updateWriteEmergencyPassword(shortEmergencyPassword)
        vm.updateWriteEmergencyPasswordConfirmation(shortEmergencyPassword)
        vm.updateWriteEmergencyMessage("emergency-hello")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_duressEnabled_mismatchedEmergencyPasswords_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.updateWriteEmergencyPassword("emergency1")
        vm.updateWriteEmergencyPasswordConfirmation("emergency2")
        vm.updateWriteEmergencyMessage("emergency-hello")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_duressEnabled_sameMainAndEmergencyPassword_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.updateWriteEmergencyPassword(pw)
        vm.updateWriteEmergencyPasswordConfirmation(pw)
        vm.updateWriteEmergencyMessage("emergency-hello")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_duressEnabled_blankEmergencyMessage_setsWriteStatusError() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("hello")
        vm.updateWriteIsDuressEnabled(true)
        vm.updateWriteEmergencyPassword("emergency-pw")
        vm.updateWriteEmergencyPasswordConfirmation("emergency-pw")
        vm.beginWriteScan()

        assertTrue(vm.uiState.value.writeStatus.isError)
        assertNull(vm.uiState.value.pendingScanAction)
    }

    @Test
    fun beginWriteScan_duressEnabled_validInputs_setsPendingWriteActionWithDuress() {
        val vm = viewModel()
        val pw = "validpassword"
        vm.updateWritePassword(pw)
        vm.updateWritePasswordConfirmation(pw)
        vm.updateWriteMessage("hello world")
        vm.updateWriteIsDuressEnabled(true)
        vm.updateWriteEmergencyPassword("emergency-pw")
        vm.updateWriteEmergencyPasswordConfirmation("emergency-pw")
        vm.updateWriteEmergencyMessage("emergency seed")
        vm.beginWriteScan()

        assertFalse(vm.uiState.value.writeStatus.isError)
        val action = vm.uiState.value.pendingScanAction
        assertTrue(action is PendingScanAction.Write)
        val writeAction = action as PendingScanAction.Write
        assertTrue(writeAction.isDuress)
        assertEquals("emergency-pw", writeAction.emergencyPassword)
        assertEquals("emergency seed", writeAction.emergencyMessage)
    }
}
