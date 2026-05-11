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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import dev.alsatianconsulting.NFCommunicator.AppScreen
import dev.alsatianconsulting.NFCommunicator.MIN_PASSWORD_LENGTH
import dev.alsatianconsulting.NFCommunicator.MainUiState
import dev.alsatianconsulting.NFCommunicator.StatusMessage
import dev.alsatianconsulting.NFCommunicator.domain.SecureMessageCodec
import dev.alsatianconsulting.NFCommunicator.domain.StorageBackend
import dev.alsatianconsulting.NFCommunicator.domain.TagInfo
import kotlinx.coroutines.delay

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
) {
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
                )

                AppScreen.Write -> WriteScreen(
                    uiState = uiState,
                    onWritePasswordChanged = onWritePasswordChanged,
                    onWritePasswordConfirmationChanged = onWritePasswordConfirmationChanged,
                    onWriteMessageChanged = onWriteMessageChanged,
                    onStartWrite = onStartWrite,
                    onClearCard = onClearCard,
                    onCancelPendingScan = onCancelPendingScan,
                )
            }
        }
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

        if (uiState.pendingScanAction != null) {
            TextButton(
                onClick = onCancelPendingScan,
                enabled = !uiState.isProcessing,
            ) {
                Text("Cancel pending scan")
            }
        }

        StatusPanel(uiState.readStatus)

        uiState.readMessage?.let { message ->
            OutlinedCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                            // Use an empty label so no app can identify the content origin.
                            val clip = ClipData.newPlainText("", message)
                            // On API 33+ mark the clip as sensitive so the OS redacts it in
                            // clipboard access toasts shown to other apps (MASVS-STORAGE-4).
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
                            // Restart the 60-second clipboard-clear timer.
                            clipboardClearKey += 1
                        },
                    ) {
                        Text("Copy Message")
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Write one encrypted plain-text message per tag. Existing app data will be overwritten if the encrypted message fits.",
            style = MaterialTheme.typography.bodyMedium,
        )

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
                    uiState.writeMessage.isNotBlank(),
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
