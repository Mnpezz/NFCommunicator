package dev.alsatianconsulting.NFCommunicator

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
}
