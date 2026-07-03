@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.alsatianconsulting.NFCommunicator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import dev.alsatianconsulting.NFCommunicator.AppScreen
import dev.alsatianconsulting.NFCommunicator.MIN_PASSWORD_LENGTH
import dev.alsatianconsulting.NFCommunicator.MainUiState
import dev.alsatianconsulting.NFCommunicator.StatusMessage
import dev.alsatianconsulting.NFCommunicator.QrTargetField
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.alsatianconsulting.NFCommunicator.domain.Bip39Compressor
import dev.alsatianconsulting.NFCommunicator.domain.KeyParser
import dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec
import dev.alsatianconsulting.NFCommunicator.domain.StorageBackend
import dev.alsatianconsulting.NFCommunicator.domain.TagInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.RadioButton
import kotlinx.coroutines.delay

@Composable
fun QrScanIconButton(
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                onClick()
            } else {
                Toast.makeText(context, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
            }
        }
    )

    IconButton(
        onClick = {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                onClick()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    ) {
        Icon(
            imageVector = Icons.Default.QrCodeScanner,
            contentDescription = "Scan QR Code"
        )
    }
}

@Composable
fun NfcCommunicatorApp(
    uiState: MainUiState,
    onScreenSelected: (AppScreen) -> Unit,
    onReadPasswordChanged: (String) -> Unit,
    onWritePasswordChanged: (String) -> Unit,
    onWritePasswordConfirmationChanged: (String) -> Unit,
    onWriteMessageChanged: (String) -> Unit,
    onStartRead: () -> Unit,
    onStartWrite: () -> Unit,
    onClearCard: () -> Unit,
    onCancelPendingScan: () -> Unit,
    onGenerateMnemonic: (Int) -> Unit,
    onUseGeneratedMnemonic: () -> Unit,
    onClearGeneratedMnemonic: () -> Unit,
    onRefreshBalance: () -> Unit,
    onSendRecipientChanged: (String) -> Unit,
    onSendAmountChanged: (String) -> Unit,
    onSendFeeRateChanged: (String) -> Unit,
    onConfirmPasswordChanged: (String) -> Unit,
    onInitiateSend: () -> Unit,
    onConfirmSendAndScan: () -> Unit,
    onCancelSend: () -> Unit,
    onCloseWallet: () -> Unit,
    onStartMultiNfcUnlock: () -> Unit,
    onWriteIsMultiNfcSplitChanged: (Boolean) -> Unit,
    onWriteMultiNfcNChanged: (Int) -> Unit,
    onWriteMultiNfcKChanged: (Int) -> Unit,
    onBreezApiKeyChanged: (String) -> Unit = {},
    onBreezNetworkChanged: (String) -> Unit = {},
    onBreezConnect: (java.io.File) -> Unit = {},
    onBreezDisconnect: () -> Unit = {},
    onFetchBreezBalance: () -> Unit = {},
    onBreezReceiveAmountChanged: (String) -> Unit = {},
    onBreezGenerateInvoice: () -> Unit = {},
    onBreezSendDestinationChanged: (String) -> Unit = {},
    onBreezSendAmountChanged: (String) -> Unit = {},
    onBreezPrepareSend: () -> Unit = {},
    onBreezConfirmSend: () -> Unit = {},
    onBreezClearSend: () -> Unit = {},
    onBreezClearReceive: () -> Unit = {},
    onCashuMintUrlChanged: (String) -> Unit = {},
    onCashuMintAmountInputChanged: (String) -> Unit = {},
    onCashuSendAmountInputChanged: (String) -> Unit = {},
    onCashuReceiveTokenInputChanged: (String) -> Unit = {},
    onCashuMeltInvoiceChanged: (String) -> Unit = {},
    onRequestCashuMintQuote: () -> Unit = {},
    onCheckAndClaimCashuMint: () -> Unit = {},
    onClearCashuMintQuote: () -> Unit = {},
    onGenerateCashuSendToken: () -> Unit = {},
    onClaimCashuToken: () -> Unit = {},
    onRequestCashuMeltQuote: () -> Unit = {},
    onConfirmAndExecuteMelt: () -> Unit = {},
    onClearCashuMeltQuote: () -> Unit = {},
    onClearCashuGeneratedToken: () -> Unit = {},
    onClearCashuMeltSuccess: () -> Unit = {},
    onClearCashuError: () -> Unit = {},
    onStartQrScan: (QrTargetField) -> Unit = {},
    onCancelQrScan: () -> Unit = {},
    onCompleteQrScan: (String) -> Unit = {},
    onSelectAddressType: (String) -> Unit = {},
    onToggleUtxoSelection: (String, Int) -> Unit = { _, _ -> },
    onWriteIsDuressEnabledChanged: (Boolean) -> Unit = {},
    onWriteEmergencyPasswordChanged: (String) -> Unit = {},
    onWriteEmergencyPasswordConfirmationChanged: (String) -> Unit = {},
    onWriteEmergencyMessageChanged: (String) -> Unit = {},
    onGenerateEmergencyMnemonic: () -> Unit = {},
    onUseGeneratedEmergencyMnemonic: () -> Unit = {},
    onClearGeneratedEmergencyMnemonic: () -> Unit = {},
    onApproveNostrRequestWithCurrent: () -> Unit = {},
    onRejectNostrRequest: () -> Unit = {},
    onAutoSignRememberChanged: (Boolean) -> Unit = {},
    onClearAutoSignRules: () -> Unit = {},
    onToggleAutoSignKind22242: (Boolean) -> Unit = {},
    onToggleAutoSignKind10050: (Boolean) -> Unit = {},
    onToggleAutoSignKind31234: (Boolean) -> Unit = {},
    onToggleAutoSignKind5: (Boolean) -> Unit = {},
    onToggleAutoSignNipEncrypt: (Boolean) -> Unit = {},
    onToggleAutoSignNipDecrypt: (Boolean) -> Unit = {},
) {
    if (uiState.nostrSignerRequest != null) {
        NostrSignerScreen(
            uiState = uiState,
            onReadPasswordChanged = onReadPasswordChanged,
            onStartRead = onStartRead,
            onCancelPendingScan = onCancelPendingScan,
            onApprove = onApproveNostrRequestWithCurrent,
            onReject = onRejectNostrRequest,
            onAutoSignRememberChanged = onAutoSignRememberChanged
        )
        return
    }

    val scrollState = rememberScrollState()
    val interactionEnabled = !uiState.isProcessing && uiState.pendingScanAction == null
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.pendingPrompt?.let { prompt ->
                StatusPanel(
                    status = StatusMessage(prompt, isError = false),
                )
            }

            uiState.lastTagInfo?.let { tagInfo ->
                TagSummaryPanel(tagInfo = tagInfo)
            }

            TabRow(
                selectedTabIndex = uiState.selectedScreen.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Tab(
                    selected = uiState.selectedScreen == AppScreen.Read,
                    enabled = interactionEnabled,
                    onClick = { onScreenSelected(AppScreen.Read) },
                    text = { Text("Read") },
                )
                Tab(
                    selected = uiState.selectedScreen == AppScreen.Write,
                    enabled = interactionEnabled,
                    onClick = { onScreenSelected(AppScreen.Write) },
                    text = { Text("Write") },
                )
            }

            when (uiState.selectedScreen) {
                AppScreen.Read -> ReadScreen(
                    uiState = uiState,
                    onReadPasswordChanged = onReadPasswordChanged,
                    onStartRead = onStartRead,
                    onClearCard = onClearCard,
                    onCancelPendingScan = onCancelPendingScan,
                    onRefreshBalance = onRefreshBalance,
                    onSendRecipientChanged = onSendRecipientChanged,
                    onSendAmountChanged = onSendAmountChanged,
                    onSendFeeRateChanged = onSendFeeRateChanged,
                    onConfirmPasswordChanged = onConfirmPasswordChanged,
                    onInitiateSend = onInitiateSend,
                    onConfirmSendAndScan = onConfirmSendAndScan,
                    onCancelSend = onCancelSend,
                    onCloseWallet = onCloseWallet,
                    onStartMultiNfcUnlock = onStartMultiNfcUnlock,
                    onBreezApiKeyChanged = onBreezApiKeyChanged,
                    onBreezNetworkChanged = onBreezNetworkChanged,
                    onBreezConnect = onBreezConnect,
                    onBreezDisconnect = onBreezDisconnect,
                    onFetchBreezBalance = onFetchBreezBalance,
                    onBreezReceiveAmountChanged = onBreezReceiveAmountChanged,
                    onBreezGenerateInvoice = onBreezGenerateInvoice,
                    onBreezSendDestinationChanged = onBreezSendDestinationChanged,
                    onBreezSendAmountChanged = onBreezSendAmountChanged,
                    onBreezPrepareSend = onBreezPrepareSend,
                    onBreezConfirmSend = onBreezConfirmSend,
                    onBreezClearSend = onBreezClearSend,
                    onBreezClearReceive = onBreezClearReceive,
                    onCashuMintUrlChanged = onCashuMintUrlChanged,
                    onCashuMintAmountInputChanged = onCashuMintAmountInputChanged,
                    onCashuSendAmountInputChanged = onCashuSendAmountInputChanged,
                    onCashuReceiveTokenInputChanged = onCashuReceiveTokenInputChanged,
                    onCashuMeltInvoiceChanged = onCashuMeltInvoiceChanged,
                    onRequestCashuMintQuote = onRequestCashuMintQuote,
                    onCheckAndClaimCashuMint = onCheckAndClaimCashuMint,
                    onClearCashuMintQuote = onClearCashuMintQuote,
                    onGenerateCashuSendToken = onGenerateCashuSendToken,
                    onClaimCashuToken = onClaimCashuToken,
                    onRequestCashuMeltQuote = onRequestCashuMeltQuote,
                    onConfirmAndExecuteMelt = onConfirmAndExecuteMelt,
                    onClearCashuMeltQuote = onClearCashuMeltQuote,
                    onClearCashuGeneratedToken = onClearCashuGeneratedToken,
                    onClearCashuMeltSuccess = onClearCashuMeltSuccess,
                    onClearCashuError = onClearCashuError,
                    onStartQrScan = onStartQrScan,
                    onSelectAddressType = onSelectAddressType,
                    onToggleUtxoSelection = onToggleUtxoSelection,
                    onClearAutoSignRules = onClearAutoSignRules,
                    onToggleAutoSignKind22242 = onToggleAutoSignKind22242,
                    onToggleAutoSignKind10050 = onToggleAutoSignKind10050,
                    onToggleAutoSignKind31234 = onToggleAutoSignKind31234,
                    onToggleAutoSignKind5 = onToggleAutoSignKind5,
                    onToggleAutoSignNipEncrypt = onToggleAutoSignNipEncrypt,
                    onToggleAutoSignNipDecrypt = onToggleAutoSignNipDecrypt
                )

                AppScreen.Write -> WriteScreen(
                    uiState = uiState,
                    onWritePasswordChanged = onWritePasswordChanged,
                    onWritePasswordConfirmationChanged = onWritePasswordConfirmationChanged,
                    onWriteMessageChanged = onWriteMessageChanged,
                    onStartWrite = onStartWrite,
                    onClearCard = onClearCard,
                    onCancelPendingScan = onCancelPendingScan,
                    onGenerateMnemonic = onGenerateMnemonic,
                    onUseGeneratedMnemonic = onUseGeneratedMnemonic,
                    onClearGeneratedMnemonic = onClearGeneratedMnemonic,
                    onWriteIsMultiNfcSplitChanged = onWriteIsMultiNfcSplitChanged,
                    onWriteMultiNfcNChanged = onWriteMultiNfcNChanged,
                    onWriteMultiNfcKChanged = onWriteMultiNfcKChanged,
                    onWriteIsDuressEnabledChanged = onWriteIsDuressEnabledChanged,
                    onWriteEmergencyPasswordChanged = onWriteEmergencyPasswordChanged,
                    onWriteEmergencyPasswordConfirmationChanged = onWriteEmergencyPasswordConfirmationChanged,
                    onWriteEmergencyMessageChanged = onWriteEmergencyMessageChanged,
                    onGenerateEmergencyMnemonic = onGenerateEmergencyMnemonic,
                    onUseGeneratedEmergencyMnemonic = onUseGeneratedEmergencyMnemonic,
                    onClearGeneratedEmergencyMnemonic = onClearGeneratedEmergencyMnemonic,
                )
            }
        }
    }

    if (uiState.showQrScanner) {
        QrCodeScanner(
            onDismiss = onCancelQrScan,
            onResult = onCompleteQrScan
        )
    }
}

private const val CLIPBOARD_AUTO_CLEAR_DELAY_MS = 60_000L

@Composable
private fun ReadScreen(
    uiState: MainUiState,
    onReadPasswordChanged: (String) -> Unit,
    onStartRead: () -> Unit,
    onClearCard: () -> Unit,
    onCancelPendingScan: () -> Unit,
    onRefreshBalance: () -> Unit,
    onSendRecipientChanged: (String) -> Unit,
    onSendAmountChanged: (String) -> Unit,
    onSendFeeRateChanged: (String) -> Unit,
    onConfirmPasswordChanged: (String) -> Unit,
    onInitiateSend: () -> Unit,
    onConfirmSendAndScan: () -> Unit,
    onCancelSend: () -> Unit,
    onCloseWallet: () -> Unit,
    onStartMultiNfcUnlock: () -> Unit,
    onBreezApiKeyChanged: (String) -> Unit = {},
    onBreezNetworkChanged: (String) -> Unit = {},
    onBreezConnect: (java.io.File) -> Unit = {},
    onBreezDisconnect: () -> Unit = {},
    onFetchBreezBalance: () -> Unit = {},
    onBreezReceiveAmountChanged: (String) -> Unit = {},
    onBreezGenerateInvoice: () -> Unit = {},
    onBreezSendDestinationChanged: (String) -> Unit = {},
    onBreezSendAmountChanged: (String) -> Unit = {},
    onBreezPrepareSend: () -> Unit = {},
    onBreezConfirmSend: () -> Unit = {},
    onBreezClearSend: () -> Unit = {},
    onBreezClearReceive: () -> Unit = {},
    onCashuMintUrlChanged: (String) -> Unit = {},
    onCashuMintAmountInputChanged: (String) -> Unit = {},
    onCashuSendAmountInputChanged: (String) -> Unit = {},
    onCashuReceiveTokenInputChanged: (String) -> Unit = {},
    onCashuMeltInvoiceChanged: (String) -> Unit = {},
    onRequestCashuMintQuote: () -> Unit = {},
    onCheckAndClaimCashuMint: () -> Unit = {},
    onClearCashuMintQuote: () -> Unit = {},
    onGenerateCashuSendToken: () -> Unit = {},
    onClaimCashuToken: () -> Unit = {},
    onRequestCashuMeltQuote: () -> Unit = {},
    onConfirmAndExecuteMelt: () -> Unit = {},
    onClearCashuMeltQuote: () -> Unit = {},
    onClearCashuGeneratedToken: () -> Unit = {},
    onClearCashuMeltSuccess: () -> Unit = {},
    onClearCashuError: () -> Unit = {},
    onStartQrScan: (QrTargetField) -> Unit = {},
    onSelectAddressType: (String) -> Unit = {},
    onToggleUtxoSelection: (String, Int) -> Unit = { _, _ -> },
    onClearAutoSignRules: () -> Unit = {},
    onToggleAutoSignKind22242: (Boolean) -> Unit = {},
    onToggleAutoSignKind10050: (Boolean) -> Unit = {},
    onToggleAutoSignKind31234: (Boolean) -> Unit = {},
    onToggleAutoSignKind5: (Boolean) -> Unit = {},
    onToggleAutoSignNipEncrypt: (Boolean) -> Unit = {},
    onToggleAutoSignNipDecrypt: (Boolean) -> Unit = {}
) {
    val actionsDisabled = !uiState.canScanNfc || uiState.isProcessing || uiState.pendingScanAction != null
    val inputEnabled = !uiState.isProcessing && uiState.pendingScanAction == null
    val context = LocalContext.current

    // Tracks each "Copy" press as an incrementing key so that LaunchedEffect restarts the
    // clipboard-clear timer on every copy (PL-4).
    var clipboardClearKey by remember { mutableIntStateOf(0) }
    if (clipboardClearKey > 0) {
        LaunchedEffect(clipboardClearKey) {
            delay(CLIPBOARD_AUTO_CLEAR_DELAY_MS)
            val cb = context.getSystemService(ClipboardManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cb?.clearPrimaryClip()
            } else {
                cb?.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    val passwordTooShort = uiState.readPassword.isNotEmpty() &&
        uiState.readPassword.length < MIN_PASSWORD_LENGTH

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Hold a tag to the phone on this screen. If it contains this app's encrypted message, the app will detect it automatically. If a password is already entered, the app will try it; otherwise finish typing and tap Try Password.",
            style = MaterialTheme.typography.bodyMedium,
        )

        PasswordField(
            value = uiState.readPassword,
            onValueChange = onReadPasswordChanged,
            label = "Password",
            enabled = inputEnabled,
            isError = passwordTooShort,
            supportingText = if (passwordTooShort) "At least $MIN_PASSWORD_LENGTH characters required." else null,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStartRead,
                enabled = !actionsDisabled && uiState.readPassword.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Try Password")
            }
            OutlinedButton(
                onClick = onClearCard,
                enabled = !actionsDisabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear Card")
            }
        }

        Button(
            onClick = onStartMultiNfcUnlock,
            enabled = !actionsDisabled && uiState.readPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("Unlock Multi-NFC Wallet (SSS)")
        }

        if (uiState.pendingScanAction != null) {
            TextButton(
                onClick = onCancelPendingScan,
                enabled = !uiState.isProcessing,
            ) {
                Text("Cancel pending scan")
            }
        }

        StatusPanel(uiState.readStatus)

        if (uiState.derivedAddresses != null) {
            WalletPanel(
                uiState = uiState,
                onRefreshBalance = onRefreshBalance,
                onSendRecipientChanged = onSendRecipientChanged,
                onSendAmountChanged = onSendAmountChanged,
                onSendFeeRateChanged = onSendFeeRateChanged,
                onInitiateSend = onInitiateSend,
                onCloseWallet = onCloseWallet,
                onBreezApiKeyChanged = onBreezApiKeyChanged,
                onBreezNetworkChanged = onBreezNetworkChanged,
                onBreezConnect = onBreezConnect,
                onBreezDisconnect = onBreezDisconnect,
                onFetchBreezBalance = onFetchBreezBalance,
                onBreezReceiveAmountChanged = onBreezReceiveAmountChanged,
                onBreezGenerateInvoice = onBreezGenerateInvoice,
                onBreezSendDestinationChanged = onBreezSendDestinationChanged,
                onBreezSendAmountChanged = onBreezSendAmountChanged,
                onBreezPrepareSend = onBreezPrepareSend,
                onBreezConfirmSend = onBreezConfirmSend,
                onBreezClearSend = onBreezClearSend,
                onBreezClearReceive = onBreezClearReceive,
                onCashuMintUrlChanged = onCashuMintUrlChanged,
                onCashuMintAmountInputChanged = onCashuMintAmountInputChanged,
                onCashuSendAmountInputChanged = onCashuSendAmountInputChanged,
                onCashuReceiveTokenInputChanged = onCashuReceiveTokenInputChanged,
                onCashuMeltInvoiceChanged = onCashuMeltInvoiceChanged,
                onRequestCashuMintQuote = onRequestCashuMintQuote,
                onCheckAndClaimCashuMint = onCheckAndClaimCashuMint,
                onClearCashuMintQuote = onClearCashuMintQuote,
                onGenerateCashuSendToken = onGenerateCashuSendToken,
                onClaimCashuToken = onClaimCashuToken,
                onRequestCashuMeltQuote = onRequestCashuMeltQuote,
                onConfirmAndExecuteMelt = onConfirmAndExecuteMelt,
                onClearCashuMeltQuote = onClearCashuMeltQuote,
                onClearCashuGeneratedToken = onClearCashuGeneratedToken,
                onClearCashuMeltSuccess = onClearCashuMeltSuccess,
                onClearCashuError = onClearCashuError,
                onStartQrScan = onStartQrScan,
                onSelectAddressType = onSelectAddressType,
                onToggleUtxoSelection = onToggleUtxoSelection,
                onClearAutoSignRules = onClearAutoSignRules,
                onToggleAutoSignKind22242 = onToggleAutoSignKind22242,
                onToggleAutoSignKind10050 = onToggleAutoSignKind10050,
                onToggleAutoSignKind31234 = onToggleAutoSignKind31234,
                onToggleAutoSignKind5 = onToggleAutoSignKind5,
                onToggleAutoSignNipEncrypt = onToggleAutoSignNipEncrypt,
                onToggleAutoSignNipDecrypt = onToggleAutoSignNipDecrypt
            )
        }

        if (uiState.showConfirmBottomSheet) {
            androidx.compose.ui.window.Dialog(onDismissRequest = onCancelSend) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Confirm Transaction",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "You are sending ${uiState.sendAmount} sats to:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = uiState.sendRecipient,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Fee rate: ${uiState.sendFeeRate} sat/vB",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        PasswordField(
                            value = uiState.confirmPassword,
                            onValueChange = onConfirmPasswordChanged,
                            label = "Decryption Password",
                            enabled = true,
                            isError = uiState.confirmPassword.isNotEmpty() && uiState.confirmPassword.length < MIN_PASSWORD_LENGTH,
                            supportingText = if (uiState.confirmPassword.isNotEmpty() && uiState.confirmPassword.length < MIN_PASSWORD_LENGTH) "At least $MIN_PASSWORD_LENGTH characters required." else null,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCancelSend,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = onConfirmSendAndScan,
                                enabled = uiState.confirmPassword.length >= MIN_PASSWORD_LENGTH,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Confirm & Sign")
                            }
                        }
                    }
                }
            }
        }

        uiState.readMessage?.let { message ->
            var showPrivateDetails by remember(message) { mutableStateOf(false) }
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { showPrivateDetails = !showPrivateDetails },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (showPrivateDetails) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPrivateDetails) "Hide Sensitive Details" else "Show Sensitive Details",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Show Decrypted Mnemonic & Details",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(
                            imageVector = if (showPrivateDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (showPrivateDetails) "Collapse" else "Expand"
                        )
                    }

                    if (showPrivateDetails) {
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                        if (uiState.derivedAddresses != null) {
                            Text(
                                text = "Decrypted Bitcoin Seed Phrase",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val words = message.trim().split(Regex("\\s+"))
                            SeedPhraseGrid(words = words)

                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )

                            val watchOnlyTitle = if (uiState.derivedAddressesList != null) {
                                "Derived Watch-Only Addresses (Showing Index #${uiState.activeAddressIndex})"
                            } else {
                                "Derived Watch-Only Addresses"
                            }
                            Text(
                                text = watchOnlyTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            uiState.derivedAddresses.forEach { (type, address) ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = type,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        SelectionContainer(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = address,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                                val clip = ClipData.newPlainText("", address)
                                                clipboard?.setPrimaryClip(clip)
                                                Toast.makeText(context, "Address copied.", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.padding(0.dp)
                                        ) {
                                            Text("Copy", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    val clip = ClipData.newPlainText("", message)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        clip.description.extras = PersistableBundle().also { bundle ->
                                            bundle.putBoolean(
                                                android.content.ClipDescription.EXTRA_IS_SENSITIVE,
                                                true,
                                            )
                                        }
                                    }
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Mnemonic phrase copied.", Toast.LENGTH_SHORT).show()
                                    clipboardClearKey += 1
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Copy Seed Phrase")
                            }
                        } else {
                            Text(
                                text = "Decrypted message",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            SelectionContainer {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    val clip = ClipData.newPlainText("", message)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        clip.description.extras = PersistableBundle().also { bundle ->
                                            bundle.putBoolean(
                                                android.content.ClipDescription.EXTRA_IS_SENSITIVE,
                                                true,
                                            )
                                        }
                                    }
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Message copied.", Toast.LENGTH_SHORT).show()
                                    clipboardClearKey += 1
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Copy Message")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WriteScreen(
    uiState: MainUiState,
    onWritePasswordChanged: (String) -> Unit,
    onWritePasswordConfirmationChanged: (String) -> Unit,
    onWriteMessageChanged: (String) -> Unit,
    onStartWrite: () -> Unit,
    onClearCard: () -> Unit,
    onCancelPendingScan: () -> Unit,
    onGenerateMnemonic: (Int) -> Unit,
    onUseGeneratedMnemonic: () -> Unit,
    onClearGeneratedMnemonic: () -> Unit,
    onWriteIsMultiNfcSplitChanged: (Boolean) -> Unit,
    onWriteMultiNfcNChanged: (Int) -> Unit,
    onWriteMultiNfcKChanged: (Int) -> Unit,
    onWriteIsDuressEnabledChanged: (Boolean) -> Unit = {},
    onWriteEmergencyPasswordChanged: (String) -> Unit = {},
    onWriteEmergencyPasswordConfirmationChanged: (String) -> Unit = {},
    onWriteEmergencyMessageChanged: (String) -> Unit = {},
    onGenerateEmergencyMnemonic: () -> Unit = {},
    onUseGeneratedEmergencyMnemonic: () -> Unit = {},
    onClearGeneratedEmergencyMnemonic: () -> Unit = {},
) {
    val actionsDisabled = !uiState.canScanNfc || uiState.isProcessing || uiState.pendingScanAction != null
    val inputEnabled = !uiState.isProcessing && uiState.pendingScanAction == null
    val writePasswordTooShort = uiState.writePassword.isNotEmpty() &&
        uiState.writePassword.length < MIN_PASSWORD_LENGTH
    val passwordsMismatch = uiState.writePasswordConfirmation.isNotEmpty() &&
        uiState.writePassword != uiState.writePasswordConfirmation
    val plainTextCharacters = uiState.writeMessage.length
    val lastTagInfo = uiState.lastTagInfo
    val lastCapacity = lastTagInfo?.capacityBytes
    val lastBackend = lastTagInfo?.storageBackend
    val maxCharactersForLastTag = when {
        lastCapacity == null -> null
        lastBackend == StorageBackend.MifareClassicRaw -> SecureMessageCodec.maxPlainTextCharactersForMifareClassic(lastCapacity)
        lastBackend == StorageBackend.Ndef -> SecureMessageCodec.maxPlainTextCharactersForNdef(lastCapacity)
        else -> null
    }
    // The character estimate assumes 1 byte per character (ASCII). The hard cap is intentionally
    // not applied so that multi-byte UTF-8 input is never blocked; the real fit check happens
    // at write time using the actual UTF-8 byte count (CQ-3).
    val characterSupportingText = when (maxCharactersForLastTag) {
        null -> "Plain text only. $plainTextCharacters characters."
        else -> "Plain text only. $plainTextCharacters / ~$maxCharactersForLastTag ASCII characters."
    }

    val words = Bip39Compressor.cleanAndSplitMnemonic(uiState.writeMessage)
    val mnemonicCheckResult = runCatching { Bip39Compressor.mnemonicToEntropy(words) }
    val isWriteMnemonic = (words.size == 12 || words.size == 24) && mnemonicCheckResult.isSuccess
    val mnemonicValidationError = mnemonicCheckResult.exceptionOrNull()?.message
    val isPrivateKey = KeyParser.parsePrivateKey(uiState.writeMessage) != null

    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Write one encrypted plain-text message per tag. Existing app data will be overwritten if the encrypted message fits.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = "Seed Phrase:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { onGenerateMnemonic(12) },
                enabled = inputEnabled
            ) {
                Text("12 words")
            }
            OutlinedButton(
                onClick = { onGenerateMnemonic(24) },
                enabled = inputEnabled
            ) {
                Text("24 words")
            }
        }

        if (uiState.generatedMnemonic != null) {
            val generatedWords = uiState.generatedMnemonic.trim().split(Regex("\\s+"))
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Generated Bitcoin Seed Phrase",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Keep this phrase secret! This is the master key to your Bitcoin wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    SeedPhraseGrid(words = generatedWords)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onUseGeneratedMnemonic,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use Seed Phrase")
                        }
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                val clip = ClipData.newPlainText("", uiState.generatedMnemonic)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    clip.description.extras = PersistableBundle().also { bundle ->
                                        bundle.putBoolean(
                                            android.content.ClipDescription.EXTRA_IS_SENSITIVE,
                                            true,
                                        )
                                    }
                                }
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Mnemonic copied to clipboard.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy")
                        }
                        TextButton(
                            onClick = onClearGeneratedMnemonic
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }

        PasswordField(
            value = uiState.writePassword,
            onValueChange = onWritePasswordChanged,
            label = "Password",
            enabled = inputEnabled,
            isError = writePasswordTooShort,
            supportingText = if (writePasswordTooShort) "At least $MIN_PASSWORD_LENGTH characters required." else null,
        )

        PasswordField(
            value = uiState.writePasswordConfirmation,
            onValueChange = onWritePasswordConfirmationChanged,
            label = "Confirm password",
            enabled = inputEnabled,
            isError = passwordsMismatch,
            supportingText = if (passwordsMismatch) {
                "Passwords must match exactly."
            } else {
                null
            },
        )

        OutlinedTextField(
            value = uiState.writeMessage,
            onValueChange = onWriteMessageChanged,
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            enabled = inputEnabled,
            minLines = 6,
            maxLines = 10,
            supportingText = {
                Text(characterSupportingText)
            },
        )

        if ((words.size == 12 || words.size == 24) && !isWriteMnemonic && !isPrivateKey) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Mnemonic Validation Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text(
                            text = "Invalid BIP-39 Seed Phrase",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "You entered ${words.size} words, but validation failed: ${mnemonicValidationError ?: "unknown error"}. It will be written as raw plain text and will likely exceed your NFC tag's capacity.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (isPrivateKey) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE8F5E9), // Light green background
                    contentColor = Color(0xFF2E7D32) // Dark green text
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Private Key Valid",
                        tint = Color(0xFF2E7D32)
                    )
                    Column {
                        Text(
                            text = "Valid Private Key Format Detected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "This private key (WIF/Hex/Extended) will be written securely to your tag.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (isWriteMnemonic) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = uiState.writeIsMultiNfcSplit,
                        onCheckedChange = onWriteIsMultiNfcSplitChanged,
                        enabled = inputEnabled
                    )
                    Column {
                        Text(
                            text = "Split seed phrase (Shamir's Secret Sharing)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Requires scanning ${uiState.writeMultiNfcN} physical NFC tags sequentially to write the backup. Reconstructing the wallet requires scanning any ${uiState.writeMultiNfcK} of the ${uiState.writeMultiNfcN} tags.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (uiState.writeIsMultiNfcSplit) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Total Cards (N):",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                (2..5).forEach { nOption ->
                                    val isSelected = uiState.writeMultiNfcN == nOption
                                    OutlinedButton(
                                        onClick = { onWriteMultiNfcNChanged(nOption) },
                                        enabled = inputEnabled,
                                        colors = if (isSelected) {
                                            ButtonDefaults.outlinedButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        } else {
                                            ButtonDefaults.outlinedButtonColors()
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                    ) {
                                        Text(nOption.toString())
                                    }
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Required Cards (K):",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                (2..uiState.writeMultiNfcN).forEach { kOption ->
                                    val isSelected = uiState.writeMultiNfcK == kOption
                                    OutlinedButton(
                                        onClick = { onWriteMultiNfcKChanged(kOption) },
                                        enabled = inputEnabled,
                                        colors = if (isSelected) {
                                            ButtonDefaults.outlinedButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        } else {
                                            ButtonDefaults.outlinedButtonColors()
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                    ) {
                                        Text(kOption.toString())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = uiState.writeIsDuressEnabled,
                onCheckedChange = onWriteIsDuressEnabledChanged,
                enabled = inputEnabled
            )
            Column {
                Text(
                    text = "Enable Emergency/Duress Wallet",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Protects against physical coercion. Allows typing a secondary password to unlock a separate wallet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (uiState.writeIsDuressEnabled) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Emergency/Duress Wallet Setup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Emergency Seed:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onGenerateEmergencyMnemonic,
                            enabled = inputEnabled
                        ) {
                            Text("Generate 12 words")
                        }
                    }

                    if (uiState.generatedEmergencyMnemonic != null) {
                        val emergencyWords = uiState.generatedEmergencyMnemonic.trim().split(Regex("\\s+"))
                        OutlinedCard(
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Generated Emergency Seed Phrase",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                SeedPhraseGrid(words = emergencyWords)
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onUseGeneratedEmergencyMnemonic,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Use Seed Phrase")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                                            val clip = ClipData.newPlainText("", uiState.generatedEmergencyMnemonic)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                clip.description.extras = PersistableBundle().also { bundle ->
                                                    bundle.putBoolean(
                                                        android.content.ClipDescription.EXTRA_IS_SENSITIVE,
                                                        true,
                                                    )
                                                }
                                            }
                                            clipboard?.setPrimaryClip(clip)
                                            Toast.makeText(context, "Emergency Mnemonic copied.", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Copy")
                                    }
                                    TextButton(
                                        onClick = onClearGeneratedEmergencyMnemonic
                                    ) {
                                        Text("Close")
                                    }
                                }
                            }
                        }
                    }

                    val emergencyPasswordTooShort = uiState.writeEmergencyPassword.isNotEmpty() &&
                        uiState.writeEmergencyPassword.length < MIN_PASSWORD_LENGTH
                    val emergencyPasswordsMismatch = uiState.writeEmergencyPasswordConfirmation.isNotEmpty() &&
                        uiState.writeEmergencyPassword != uiState.writeEmergencyPasswordConfirmation
                    val mainAndEmergencySame = uiState.writeEmergencyPassword.isNotEmpty() &&
                        uiState.writePassword == uiState.writeEmergencyPassword

                    PasswordField(
                        value = uiState.writeEmergencyPassword,
                        onValueChange = onWriteEmergencyPasswordChanged,
                        label = "Emergency Password",
                        enabled = inputEnabled,
                        isError = emergencyPasswordTooShort || mainAndEmergencySame,
                        supportingText = when {
                            emergencyPasswordTooShort -> "At least $MIN_PASSWORD_LENGTH characters required."
                            mainAndEmergencySame -> "Emergency password must be different from Main password."
                            else -> null
                        }
                    )

                    PasswordField(
                        value = uiState.writeEmergencyPasswordConfirmation,
                        onValueChange = onWriteEmergencyPasswordConfirmationChanged,
                        label = "Confirm Emergency Password",
                        enabled = inputEnabled,
                        isError = emergencyPasswordsMismatch,
                        supportingText = if (emergencyPasswordsMismatch) "Passwords must match exactly." else null
                    )

                    OutlinedTextField(
                        value = uiState.writeEmergencyMessage,
                        onValueChange = onWriteEmergencyMessageChanged,
                        label = { Text("Emergency Seed Phrase / Message") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = inputEnabled,
                        minLines = 4,
                        maxLines = 6
                    )
                }
            }
        }

        OutlinedCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Size estimate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isWriteMnemonic) {
                    Text(
                        text = "BIP-39 Mnemonic detected: ${words.size} words",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Compressed to binary entropy before encryption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (words.size == 12 || words.size == 24) {
                    Text(
                        text = "Invalid BIP-39 Mnemonic: ${words.size} words",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Warning: Validation failed (spelling error or checksum mismatch). It will NOT be compressed and will be written as raw plain text, which will likely exceed your tag capacity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "Character count: $plainTextCharacters characters",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Encrypted NDEF size: ${uiState.estimatedNdefWriteSizeBytes} bytes",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Encrypted raw MIFARE Classic size: ${uiState.estimatedMifareClassicWriteSizeBytes} bytes",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (lastCapacity != null) {
                    if (maxCharactersForLastTag != null) {
                        val backendLabel = lastBackend?.label ?: "unknown"
                        Text(
                            text = "The last scanned $backendLabel tag can hold up to ~$maxCharactersForLastTag ASCII characters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (uiState.writeMessage.isNotBlank()) {
                        val estimateForLastTag = when (lastBackend) {
                            StorageBackend.MifareClassicRaw -> uiState.estimatedMifareClassicWriteSizeBytes
                            StorageBackend.Ndef -> uiState.estimatedNdefWriteSizeBytes
                            StorageBackend.Unknown, null -> null
                        }
                        val backendLabel = lastBackend?.label ?: "unknown"
                        val fits = estimateForLastTag != null && estimateForLastTag <= lastCapacity
                        val fitText = if (fits) "fits" else "does not fit"
                        Text(
                            text = if (estimateForLastTag == null) {
                                "The last scanned tag did not expose a recognized storage backend."
                            } else {
                                "Against the last scanned $backendLabel tag: $fitText ($lastCapacity bytes available)."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (estimateForLastTag == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else if (fits) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                } else {
                    Text(
                        text = "Final fit is checked again when you scan a tag.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStartWrite,
                enabled = !actionsDisabled &&
                    uiState.writePassword.isNotBlank() &&
                    !passwordsMismatch &&
                    uiState.writeMessage.isNotBlank() &&
                    (!uiState.writeIsDuressEnabled || (
                        uiState.writeEmergencyPassword.isNotBlank() &&
                        uiState.writeEmergencyPassword == uiState.writeEmergencyPasswordConfirmation &&
                        uiState.writeEmergencyMessage.isNotBlank() &&
                        uiState.writePassword != uiState.writeEmergencyPassword
                    )),
                modifier = Modifier.weight(1f),
            ) {
                Text("Write to Card")
            }
            OutlinedButton(
                onClick = onClearCard,
                enabled = !actionsDisabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear Card")
            }
        }

        if (uiState.pendingScanAction != null) {
            TextButton(
                onClick = onCancelPendingScan,
                enabled = !uiState.isProcessing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel pending scan")
            }
        }

        StatusPanel(uiState.writeStatus)
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Password,
        ),
        trailingIcon = {
            val icon = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
            val description = if (passwordVisible) "Hide password" else "Show password"
            IconButton(
                onClick = { passwordVisible = !passwordVisible },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                )
            }
        },
        isError = isError,
        supportingText = supportingText?.let { support ->
            { Text(support) }
        },
    )
}

@Composable
private fun StatusPanel(status: StatusMessage) {
    val containerColor = if (status.isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (status.isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = status.text,
                style = MaterialTheme.typography.bodyMedium,
            )
            status.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
private fun TagSummaryPanel(tagInfo: TagInfo) {
    val capacity = when {
        tagInfo.capacityBytes != null &&
            tagInfo.totalCapacityBytes != null &&
            tagInfo.totalCapacityBytes != tagInfo.capacityBytes ->
            "${tagInfo.capacityBytes} app bytes • ${tagInfo.totalCapacityBytes} total bytes"

        tagInfo.capacityBytes != null -> "${tagInfo.capacityBytes} bytes"
        tagInfo.totalCapacityBytes != null -> "${tagInfo.totalCapacityBytes} total bytes"
        else -> "unknown capacity"
    }
    val writable = when (tagInfo.isWritable) {
        true -> "writable"
        false -> "read-only"
        null -> "writability unknown"
    }
    val backend = tagInfo.storageBackend.label
    val technologies = tagInfo.technologies.joinToString(", ").ifBlank { "unknown tech" }

    OutlinedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Last scanned tag",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$backend • $capacity • $writable",
                style = MaterialTheme.typography.bodyMedium,
            )
            tagInfo.tagDescription?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = technologies,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SeedPhraseGrid(words: List<String>, modifier: Modifier = Modifier) {
    val columnCount = 2
    val itemsPerColumn = (words.size + columnCount - 1) / columnCount
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        for (col in 0 until columnCount) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (row in 0 until itemsPerColumn) {
                    val index = col * itemsPerColumn + row
                    if (index < words.size) {
                        val wordNum = index + 1
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "$wordNum.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                text = words[index],
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletPanel(
    uiState: MainUiState,
    onRefreshBalance: () -> Unit,
    onSendRecipientChanged: (String) -> Unit,
    onSendAmountChanged: (String) -> Unit,
    onSendFeeRateChanged: (String) -> Unit,
    onInitiateSend: () -> Unit,
    onCloseWallet: () -> Unit,
    onBreezApiKeyChanged: (String) -> Unit,
    onBreezNetworkChanged: (String) -> Unit,
    onBreezConnect: (java.io.File) -> Unit,
    onBreezDisconnect: () -> Unit,
    onFetchBreezBalance: () -> Unit,
    onBreezReceiveAmountChanged: (String) -> Unit,
    onBreezGenerateInvoice: () -> Unit,
    onBreezSendDestinationChanged: (String) -> Unit,
    onBreezSendAmountChanged: (String) -> Unit,
    onBreezPrepareSend: () -> Unit,
    onBreezConfirmSend: () -> Unit,
    onBreezClearSend: () -> Unit,
    onBreezClearReceive: () -> Unit,
    onCashuMintUrlChanged: (String) -> Unit,
    onCashuMintAmountInputChanged: (String) -> Unit,
    onCashuSendAmountInputChanged: (String) -> Unit,
    onCashuReceiveTokenInputChanged: (String) -> Unit,
    onCashuMeltInvoiceChanged: (String) -> Unit,
    onRequestCashuMintQuote: () -> Unit,
    onCheckAndClaimCashuMint: () -> Unit,
    onClearCashuMintQuote: () -> Unit,
    onGenerateCashuSendToken: () -> Unit,
    onClaimCashuToken: () -> Unit,
    onRequestCashuMeltQuote: () -> Unit,
    onConfirmAndExecuteMelt: () -> Unit,
    onClearCashuMeltQuote: () -> Unit,
    onClearCashuGeneratedToken: () -> Unit,
    onClearCashuMeltSuccess: () -> Unit,
    onClearCashuError: () -> Unit,
    onStartQrScan: (QrTargetField) -> Unit,
    onSelectAddressType: (String) -> Unit,
    onToggleUtxoSelection: (String, Int) -> Unit,
    onClearAutoSignRules: () -> Unit,
    onToggleAutoSignKind22242: (Boolean) -> Unit,
    onToggleAutoSignKind10050: (Boolean) -> Unit,
    onToggleAutoSignKind31234: (Boolean) -> Unit,
    onToggleAutoSignKind5: (Boolean) -> Unit,
    onToggleAutoSignNipEncrypt: (Boolean) -> Unit,
    onToggleAutoSignNipDecrypt: (Boolean) -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) } // 0: On-chain, 1: Nostr, 2: eCash

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Tab Switcher and Close Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    divider = { Spacer(modifier = Modifier.height(0.dp)) }
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("On-Chain", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Nostr", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
                    )
                    Tab(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        text = { Text("eCash", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
                    )
                }
                
                IconButton(
                    onClick = onCloseWallet,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Wallet",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (activeTab == 0) {
                // On-Chain Wallet Section
                Text(
                    text = "Bitcoin Wallet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Address Type selection dropdown
                var addressDropdownExpanded by remember { mutableStateOf(false) }
                val addressTypes = listOf(
                    "Legacy (BIP-44)",
                    "Nested SegWit (BIP-49)",
                    "Native SegWit (BIP-84)",
                    "Taproot (BIP-86)"
                )

                ExposedDropdownMenuBox(
                    expanded = addressDropdownExpanded,
                    onExpandedChange = { addressDropdownExpanded = !addressDropdownExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = uiState.activeAddressType,
                        onValueChange = {},
                        label = { Text("Active Address Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = addressDropdownExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = addressDropdownExpanded,
                        onDismissRequest = { addressDropdownExpanded = false }
                    ) {
                        addressTypes.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    onSelectAddressType(selectionOption)
                                    addressDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Display active address card with Copy button
                val activeAddress = uiState.derivedAddresses?.get(uiState.activeAddressType) ?: ""
                if (activeAddress.isNotEmpty()) {
                    val context = LocalContext.current
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val addressLabel = if (uiState.derivedAddressesList != null) {
                                    "Your Address (${uiState.activeAddressType} - Index #${uiState.activeAddressIndex})"
                                } else {
                                    "Your Address (${uiState.activeAddressType})"
                                }
                                Text(
                                    text = addressLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                SelectionContainer {
                                    Text(
                                        text = activeAddress,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 1
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val cb = context.getSystemService(ClipboardManager::class.java)
                                    cb?.setPrimaryClip(ClipData.newPlainText("Bitcoin Address", activeAddress))
                                    Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Address"
                                )
                            }
                        }
                    }
                }

                // Balance section
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Active Balance",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            val balanceText = when {
                                uiState.isFetchingBalance -> "Fetching..."
                                uiState.walletBalance != null -> "${uiState.walletBalance} sats"
                                else -> "Tap refresh to load"
                            }
                            Text(
                                text = balanceText,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(
                            onClick = onRefreshBalance,
                            enabled = !uiState.isFetchingBalance
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh balance"
                            )
                        }
                    }
                }

                // Coin Control Panel
                val utxos = uiState.walletUtxos ?: emptyList()
                var coinControlExpanded by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { coinControlExpanded = !coinControlExpanded }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (coinControlExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (coinControlExpanded) "Collapse" else "Expand"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Coin Control (${utxos.size} UTXOs)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            val selectedCount = uiState.selectedUtxoIds.size
                            val selectedSum = utxos
                                .filter { uiState.selectedUtxoIds.contains("${it.txid}:${it.vout}") }
                                .sumOf { it.value }
                            
                            Text(
                                text = "Selected: $selectedSum sats ($selectedCount)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (coinControlExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (utxos.isEmpty()) {
                                Text(
                                    text = "No UTXOs available. Receive some funds first.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    utxos.forEach { utxo ->
                                        val utxoId = "${utxo.txid}:${utxo.vout}"
                                        val isSelected = uiState.selectedUtxoIds.contains(utxoId)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onToggleUtxoSelection(utxo.txid, utxo.vout) }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { onToggleUtxoSelection(utxo.txid, utxo.vout) }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${utxo.value} sats",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = if (utxo.confirmed) "Confirmed" else "Unconfirmed",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (utxo.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Text(
                                                    text = "Tx: ${utxo.txid.take(8)}...${utxo.txid.takeLast(8)} vout: ${utxo.vout}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                                Text(
                                                    text = "Address: ${utxo.address.take(8)}...${utxo.address.takeLast(8)} (Index #${utxo.addressIndex})",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Send Form
                Text(
                    text = "Send Bitcoin",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = uiState.sendRecipient,
                    onValueChange = onSendRecipientChanged,
                    label = { Text("Recipient Address (Bech32)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("bc1q...") },
                    trailingIcon = {
                        QrScanIconButton(onClick = { onStartQrScan(QrTargetField.BtcRecipient) })
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.sendAmount,
                        onValueChange = onSendAmountChanged,
                        label = { Text("Amount (Sats)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = uiState.sendFeeRate,
                        onValueChange = onSendFeeRateChanged,
                        label = { Text("Fee Rate (sat/vB)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                if (uiState.broadcastError != null) {
                    Text(
                        text = uiState.broadcastError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (uiState.broadcastTxId != null) {
                    SelectionContainer {
                        Text(
                            text = "Tx Sent! ID: ${uiState.broadcastTxId}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = onInitiateSend,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.sendRecipient.isNotBlank() && uiState.sendAmount.isNotBlank()
                ) {
                    Text("Send Transaction")
                }
            } else if (activeTab == 1) {
                NostrWalletSection(
                    uiState = uiState,
                    onCashuMintUrlChanged = onCashuMintUrlChanged,
                    onCashuMintAmountInputChanged = onCashuMintAmountInputChanged,
                    onCashuSendAmountInputChanged = onCashuSendAmountInputChanged,
                    onCashuReceiveTokenInputChanged = onCashuReceiveTokenInputChanged,
                    onCashuMeltInvoiceChanged = onCashuMeltInvoiceChanged,
                    onRequestCashuMintQuote = onRequestCashuMintQuote,
                    onCheckAndClaimCashuMint = onCheckAndClaimCashuMint,
                    onClearCashuMintQuote = onClearCashuMintQuote,
                    onGenerateCashuSendToken = onGenerateCashuSendToken,
                    onClaimCashuToken = onClaimCashuToken,
                    onRequestCashuMeltQuote = onRequestCashuMeltQuote,
                    onConfirmAndExecuteMelt = onConfirmAndExecuteMelt,
                    onClearCashuMeltQuote = onClearCashuMeltQuote,
                    onClearCashuGeneratedToken = onClearCashuGeneratedToken,
                    onClearCashuMeltSuccess = onClearCashuMeltSuccess,
                    onClearCashuError = onClearCashuError,
                    onStartQrScan = onStartQrScan,
                    onClearAutoSignRules = onClearAutoSignRules,
                    onToggleAutoSignKind22242 = onToggleAutoSignKind22242,
                    onToggleAutoSignKind10050 = onToggleAutoSignKind10050,
                    onToggleAutoSignKind31234 = onToggleAutoSignKind31234,
                    onToggleAutoSignKind5 = onToggleAutoSignKind5,
                    onToggleAutoSignNipEncrypt = onToggleAutoSignNipEncrypt,
                    onToggleAutoSignNipDecrypt = onToggleAutoSignNipDecrypt
                )
            } else if (activeTab == 2) {
                EcashWalletSection(
                    uiState = uiState,
                    onCashuMintUrlChanged = onCashuMintUrlChanged,
                    onCashuMintAmountInputChanged = onCashuMintAmountInputChanged,
                    onCashuSendAmountInputChanged = onCashuSendAmountInputChanged,
                    onCashuReceiveTokenInputChanged = onCashuReceiveTokenInputChanged,
                    onCashuMeltInvoiceChanged = onCashuMeltInvoiceChanged,
                    onRequestCashuMintQuote = onRequestCashuMintQuote,
                    onCheckAndClaimCashuMint = onCheckAndClaimCashuMint,
                    onClearCashuMintQuote = onClearCashuMintQuote,
                    onGenerateCashuSendToken = onGenerateCashuSendToken,
                    onClaimCashuToken = onClaimCashuToken,
                    onRequestCashuMeltQuote = onRequestCashuMeltQuote,
                    onConfirmAndExecuteMelt = onConfirmAndExecuteMelt,
                    onClearCashuMeltQuote = onClearCashuMeltQuote,
                    onClearCashuGeneratedToken = onClearCashuGeneratedToken,
                    onClearCashuMeltSuccess = onClearCashuMeltSuccess,
                    onClearCashuError = onClearCashuError,
                    onStartQrScan = onStartQrScan
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NostrWalletSection(
    uiState: MainUiState,
    onCashuMintUrlChanged: (String) -> Unit,
    onCashuMintAmountInputChanged: (String) -> Unit,
    onCashuSendAmountInputChanged: (String) -> Unit,
    onCashuReceiveTokenInputChanged: (String) -> Unit,
    onCashuMeltInvoiceChanged: (String) -> Unit,
    onRequestCashuMintQuote: () -> Unit,
    onCheckAndClaimCashuMint: () -> Unit,
    onClearCashuMintQuote: () -> Unit,
    onGenerateCashuSendToken: () -> Unit,
    onClaimCashuToken: () -> Unit,
    onRequestCashuMeltQuote: () -> Unit,
    onConfirmAndExecuteMelt: () -> Unit,
    onClearCashuMeltQuote: () -> Unit,
    onClearCashuGeneratedToken: () -> Unit,
    onClearCashuMeltSuccess: () -> Unit,
    onClearCashuError: () -> Unit,
    onStartQrScan: (QrTargetField) -> Unit,
    onClearAutoSignRules: () -> Unit,
    onToggleAutoSignKind22242: (Boolean) -> Unit,
    onToggleAutoSignKind10050: (Boolean) -> Unit,
    onToggleAutoSignKind31234: (Boolean) -> Unit,
    onToggleAutoSignKind5: (Boolean) -> Unit,
    onToggleAutoSignNipEncrypt: (Boolean) -> Unit,
    onToggleAutoSignNipDecrypt: (Boolean) -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var showNsec by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Nostr Wallet Identity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Info Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Your Nostr identity is derived deterministically from your seed phrase (NIP-06). This allows you to restore your profile and ecash wallets on any Nostr-compatible device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Public Key (npub) Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Nostr Public Key (npub)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                SelectionContainer {
                    Text(
                        text = uiState.nostrNpub ?: "Not loaded",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = {
                        uiState.nostrNpub?.let {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.nostrNpub != null
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy npub",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Copy npub Address")
                }
            }
        }

        // Hex Public Key Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Hex Public Key",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                SelectionContainer {
                    Text(
                        text = uiState.nostrPubkeyHex ?: "Not loaded",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedButton(
                    onClick = {
                        uiState.nostrPubkeyHex?.let {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.nostrPubkeyHex != null
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy hex pubkey",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Hex Pubkey")
                }
            }
        }

        // Private Key (nsec) Collapsible Card
        OutlinedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { showNsec = !showNsec },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showNsec) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showNsec) "Hide Private Key" else "Show Private Key",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Private Key (nsec)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Icon(
                        imageVector = if (showNsec) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (showNsec) "Collapse" else "Expand"
                    )
                }

                if (showNsec) {
                    SelectionContainer {
                        Text(
                            text = uiState.nostrNsec ?: "Not loaded",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Button(
                        onClick = {
                            uiState.nostrNsec?.let {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        enabled = uiState.nostrNsec != null
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy nsec",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Copy nsec Private Key")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Nostr Auto-Sign Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Configure which event types can be signed or encrypted/decrypted automatically without manual authorization when your wallet is unlocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

                // Kind 22242 Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Client Auth (Kind 22242)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allows automatic login/authentication to other Nostr clients.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSignKind22242,
                        onCheckedChange = onToggleAutoSignKind22242
                    )
                }

                // Kind 10050 Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Telemetry (Kind 10050)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allows automatic background status/telemetry updates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSignKind10050,
                        onCheckedChange = onToggleAutoSignKind10050
                    )
                }

                // Kind 31234 (Drafts) Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Drafts (Kind 31234)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allows automatic background draft saves for editors.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSignKind31234,
                        onCheckedChange = onToggleAutoSignKind31234
                    )
                }

                // Kind 5 (Deletions) Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Deletions (Kind 5)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allows automatic draft deletion when notes are published.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSignKind5,
                        onCheckedChange = onToggleAutoSignKind5
                    )
                }

                // Encrypt Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Direct Message Encryption",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allows automatic NIP-04/NIP-44 direct message encryption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSignNipEncrypt,
                        onCheckedChange = onToggleAutoSignNipEncrypt
                    )
                }

                // Decrypt Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Direct Message Decryption",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allows automatic NIP-04/NIP-44 direct message decryption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSignNipDecrypt,
                        onCheckedChange = onToggleAutoSignNipDecrypt
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = onClearAutoSignRules,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset All Auto-Sign Settings")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EcashWalletSection(
    uiState: MainUiState,
    onCashuMintUrlChanged: (String) -> Unit,
    onCashuMintAmountInputChanged: (String) -> Unit,
    onCashuSendAmountInputChanged: (String) -> Unit,
    onCashuReceiveTokenInputChanged: (String) -> Unit,
    onCashuMeltInvoiceChanged: (String) -> Unit,
    onRequestCashuMintQuote: () -> Unit,
    onCheckAndClaimCashuMint: () -> Unit,
    onClearCashuMintQuote: () -> Unit,
    onGenerateCashuSendToken: () -> Unit,
    onClaimCashuToken: () -> Unit,
    onRequestCashuMeltQuote: () -> Unit,
    onConfirmAndExecuteMelt: () -> Unit,
    onClearCashuMeltQuote: () -> Unit,
    onClearCashuGeneratedToken: () -> Unit,
    onClearCashuMeltSuccess: () -> Unit,
    onClearCashuError: () -> Unit,
    onStartQrScan: (QrTargetField) -> Unit,
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Cashu eCash Wallet Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Cashu Wallet",
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Cashu eCash Wallet (NIP-60)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // Balance Display
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Wallet Balance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${uiState.cashuBalanceSat} Sats",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Mint configuration dropdown
            var expanded by remember { mutableStateOf(false) }
            val popularMints = remember {
                listOf(
                    "https://mint.minibits.cash/Bitcoin" to "Minibits Bitcoin Mint (Most Reliable)",
                    "https://legend.lnbits.com/cashu/api/v1/4GGejPMW6bXqg2P48W3C5G" to "LNbits Demo Mint",
                    "https://mint.coinos.io" to "Coinos Mint",
                    "https://cashu.my2sats.space" to "My2Sats Mint",
                    "https://8333.space:5000" to "8333.space Mint"
                )
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = uiState.cashuMintUrl,
                    onValueChange = onCashuMintUrlChanged,
                    label = { Text("Active Mint URL") },
                    placeholder = { Text("https://mint.minibits.cash/Bitcoin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    popularMints.forEach { (url, label) ->
                        DropdownMenuItem(
                            text = {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onCashuMintUrlChanged(url)
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Error and Loading indicators
            if (uiState.cashuLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                }
            }

            if (uiState.cashuError != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = uiState.cashuError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearCashuError) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Sub-operation Selector
            var ecashOpTab by remember { mutableStateOf(0) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf("Mint", "Send", "Receive", "Pay Invoice")
                tabs.forEachIndexed { index, label ->
                    val isSelected = ecashOpTab == index
                    Button(
                        onClick = { ecashOpTab = index },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Sub-operation panels
            when (ecashOpTab) {
                0 -> {
                    // Mint
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Mint eCash (via Lightning)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        if (uiState.cashuMintQuote == null) {
                            OutlinedTextField(
                                value = uiState.cashuMintAmountInput,
                                onValueChange = onCashuMintAmountInputChanged,
                                label = { Text("Amount (Sats)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = onRequestCashuMintQuote,
                                enabled = uiState.cashuMintAmountInput.toLongOrNull()?.let { it > 0 } ?: false,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Request Mint Invoice")
                            }
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Pay this Lightning invoice of ${uiState.cashuMintQuoteAmountSat} sats:",
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    SelectionContainer {
                                        Text(
                                            text = uiState.cashuMintQuote?.request ?: "",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            ),
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 5,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                clipboardManager.setText(
                                                    androidx.compose.ui.text.AnnotatedString(uiState.cashuMintQuote?.request ?: "")
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Invoice",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Copy")
                                        }

                                        Button(
                                            onClick = onCheckAndClaimCashuMint,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Claim",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Claim")
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = onClearCashuMintQuote,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Cancel / Clear")
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Send
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Send eCash (Generate Token)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        if (uiState.cashuGeneratedToken == null) {
                            OutlinedTextField(
                                value = uiState.cashuSendAmountInput,
                                onValueChange = onCashuSendAmountInputChanged,
                                label = { Text("Amount (Sats)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = onGenerateCashuSendToken,
                                enabled = uiState.cashuSendAmountInput.toLongOrNull()?.let { it > 0 && it <= uiState.cashuBalanceSat } ?: false,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Generate eCash Token")
                            }
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Share this token with the recipient:",
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    SelectionContainer {
                                        Text(
                                            text = uiState.cashuGeneratedToken ?: "",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            ),
                                            color = MaterialTheme.colorScheme.secondary,
                                            maxLines = 5,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            clipboardManager.setText(
                                                androidx.compose.ui.text.AnnotatedString(uiState.cashuGeneratedToken ?: "")
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy Token",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Copy Token")
                                    }

                                    OutlinedButton(
                                        onClick = onClearCashuGeneratedToken,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Done")
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Receive
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Receive eCash (Paste Token)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = uiState.cashuReceiveTokenInput,
                            onValueChange = onCashuReceiveTokenInputChanged,
                            label = { Text("Cashu Token (starts with cashuA)") },
                            placeholder = { Text("cashuA...") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                            trailingIcon = {
                                QrScanIconButton(onClick = { onStartQrScan(QrTargetField.CashuReceiveToken) })
                            }
                        )

                        Button(
                            onClick = onClaimCashuToken,
                            enabled = uiState.cashuReceiveTokenInput.trim().startsWith("cashuA"),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Claim Token")
                        }
                    }
                }
                3 -> {
                    // Pay Invoice (Melt)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Pay Lightning Invoice (Melt)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        if (uiState.cashuMeltQuote == null && uiState.cashuMeltSuccessPreimage == null) {
                            OutlinedTextField(
                                value = uiState.cashuMeltInvoice,
                                onValueChange = onCashuMeltInvoiceChanged,
                                label = { Text("Lightning Invoice (lnbc...)") },
                                placeholder = { Text("lnbc...") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4,
                                trailingIcon = {
                                    QrScanIconButton(onClick = { onStartQrScan(QrTargetField.CashuMeltInvoice) })
                                }
                            )

                            Button(
                                onClick = onRequestCashuMeltQuote,
                                enabled = uiState.cashuMeltInvoice.trim().isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Request Pay Quote")
                            }
                        } else if (uiState.cashuMeltQuote != null) {
                            val quote = uiState.cashuMeltQuote!!
                            val total = quote.amount + quote.feeReserve
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Confirm Payment Detail:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(text = "Amount: ${quote.amount} Sats", style = MaterialTheme.typography.bodyMedium)
                                    Text(text = "Fee Reserve: ${quote.feeReserve} Sats", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "Total Max Cost: $total Sats",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = onClearCashuMeltQuote,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Cancel")
                                        }

                                        Button(
                                            onClick = onConfirmAndExecuteMelt,
                                            enabled = uiState.cashuBalanceSat >= total,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Confirm & Pay")
                                        }
                                    }
                                }
                            }
                        } else if (uiState.cashuMeltSuccessPreimage != null) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Payment Succeeded!",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Preimage:",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    SelectionContainer {
                                        Text(
                                            text = uiState.cashuMeltSuccessPreimage ?: "",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        )
                                    }

                                    Button(
                                        onClick = onClearCashuMeltSuccess,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("OK")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun NostrSignerScreen(
    uiState: MainUiState,
    onReadPasswordChanged: (String) -> Unit,
    onStartRead: () -> Unit,
    onCancelPendingScan: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onAutoSignRememberChanged: (Boolean) -> Unit
) {
    val request = uiState.nostrSignerRequest ?: return
    val scrollState = rememberScrollState()
    val isLocked = uiState.readMessage == null
    val actionsDisabled = !uiState.canScanNfc || uiState.isProcessing || uiState.pendingScanAction != null
    val inputEnabled = !uiState.isProcessing && uiState.pendingScanAction == null

    var rawEventExpanded by remember { mutableStateOf(false) }

    val eventDetails = remember(request.eventJson) {
        runCatching {
            request.eventJson?.let { jsonStr ->
                val obj = org.json.JSONObject(jsonStr)
                val kind = obj.optInt("kind", -1)
                val content = obj.optString("content", "")
                val tags = obj.optJSONArray("tags")?.toString() ?: "[]"
                val kindDesc = when (kind) {
                    0 -> "Metadata (NIP-01)"
                    1 -> "Short Text Note (NIP-01)"
                    3 -> "Follow List (NIP-02)"
                    4 -> "Encrypted Direct Message (NIP-04)"
                    6 -> "Repost (NIP-18)"
                    7 -> "Reaction (NIP-25)"
                    10002 -> "Relay List Metadata (NIP-65)"
                    else -> "Kind $kind"
                }
                Triple(kindDesc, content, tags)
            }
        }.getOrNull()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header / App Name / Title
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Nostr Signer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Secure Hardware-backed Authorization",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Calling Package Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "REQUEST FROM APPLICATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = request.callingPackage ?: "Unknown Client",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Operation Type Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Operation:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Text(
                                text = request.type.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Request details display
                    when (request.type) {
                        "get_public_key" -> {
                            Text(
                                text = "The application wants to read your public key/identity to establish connection.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        "sign_event" -> {
                            if (eventDetails != null) {
                                Text(
                                    text = "Event: ${eventDetails.first}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                if (eventDetails.second.isNotEmpty()) {
                                    Text(
                                        text = "Content: ${eventDetails.second}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                                Text(
                                    text = "Tags: ${eventDetails.third}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "The application wants to sign a Nostr event.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            // Expandable raw JSON block
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { rawEventExpanded = !rawEventExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Raw Event JSON Details",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (rawEventExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Toggle Raw Event JSON"
                                )
                            }
                            if (rawEventExpanded) {
                                SelectionContainer {
                                    Text(
                                        text = request.eventJson ?: "{}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }
                        "nip04_encrypt" -> {
                            Text(
                                text = "Recipient Pubkey: ${request.destPubkey}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Plaintext to encrypt: ${request.plaintext}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        "nip04_decrypt" -> {
                            Text(
                                text = "Sender Pubkey: ${request.destPubkey}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Ciphertext to decrypt: ${request.ciphertext}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }


            Spacer(modifier = Modifier.height(8.dp))

            // Wallet State Card (Security Check)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isLocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isLocked) "🔒 Keys Locked" else "🔓 Keys Unlocked",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLocked) {
                        Text(
                            text = "To authorize this request, enter your tag password and tap your offline NFC card to the phone.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PasswordField(
                            value = uiState.readPassword,
                            onValueChange = onReadPasswordChanged,
                            label = "Tag Password",
                            enabled = inputEnabled,
                            isError = uiState.readPassword.isNotEmpty() && uiState.readPassword.length < MIN_PASSWORD_LENGTH,
                            supportingText = if (uiState.readPassword.isNotEmpty() && uiState.readPassword.length < MIN_PASSWORD_LENGTH) "At least $MIN_PASSWORD_LENGTH characters required." else null
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onStartRead,
                                enabled = !actionsDisabled && uiState.readPassword.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Scan Card")
                            }
                            OutlinedButton(
                                onClick = onReject,
                                enabled = !actionsDisabled,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reject")
                            }
                        }
                        if (uiState.pendingScanAction != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = onCancelPendingScan,
                                enabled = !uiState.isProcessing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel pending scan")
                            }
                        }
                    } else {
                        Text(
                            text = "Identity: ${uiState.nostrNpub}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50), // Green for authorize
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Authorize")
                            }
                            OutlinedButton(
                                onClick = onReject,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }

            // Pending Scan prompt panel
            uiState.pendingPrompt?.let { prompt ->
                StatusPanel(
                    status = StatusMessage(prompt, isError = false)
                )
            }
        }
    }
}
