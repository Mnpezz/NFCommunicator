package dev.alsatianconsulting.NFCommunicator.domain

import java.io.IOException

data class DiagnosticMessage(
    val statusMessage: String,
    val detail: String? = null,
)

object NfcDiagnostics {
    fun unexpectedFailure(action: String, error: Throwable): DiagnosticMessage =
        DiagnosticMessage(
            statusMessage = "Unexpected error while trying to ${action.lowercase()} the tag.",
            detail = error.message ?: error::class.java.simpleName,
        )

    fun ndefNoMessageFound(isEmpty: Boolean): DiagnosticMessage =
        DiagnosticMessage(
            statusMessage = "No encrypted message from this app was found on the tag.",
            detail =
                if (isEmpty) {
                    "The tag was readable, but it did not contain any NDEF records."
                } else {
                    "The tag was readable, but none of its NDEF records matched this app's encrypted message format."
                },
        )

    fun unsupportedRead(technologies: List<String>): DiagnosticMessage =
        unsupportedOperation(
            technologies = technologies,
            statusMessage = "This tag cannot be read on this phone in the selected mode.",
            action = "read",
        )

    fun unsupportedWrite(technologies: List<String>): DiagnosticMessage =
        unsupportedOperation(
            technologies = technologies,
            statusMessage = "This tag cannot be written on this phone in the selected mode.",
            action = "write",
        )

    fun unsupportedClear(technologies: List<String>): DiagnosticMessage =
        unsupportedOperation(
            technologies = technologies,
            statusMessage = "This tag cannot be cleared on this phone in the selected mode.",
            action = "clear",
        )

    fun mifareAuthenticationFailure(): DiagnosticMessage =
        DiagnosticMessage(
            statusMessage = "This MIFARE Classic tag could not be authenticated with common keys.",
            detail = "The app tried the default, NFC Forum, and MAD keys. The tag may use custom sector keys.",
        )

    fun mifarePayloadFailure(error: InvalidPayloadException): DiagnosticMessage =
        when (error) {
            is MifareHeaderNotFoundException ->
                DiagnosticMessage(
                    statusMessage = "No encrypted message from this app was found on the tag.",
                    detail =
                        if (error.isTooShort) {
                            "The raw MIFARE storage was readable, but it did not contain enough data for this app's message header."
                        } else {
                            "Raw MIFARE data was present, but it was not written in this app's encrypted message format."
                        },
                )

            is MifarePayloadTruncatedException ->
                DiagnosticMessage(
                    statusMessage = "The tag does not contain a compatible raw MIFARE Classic encrypted message.",
                    detail = "The app found its raw MIFARE header but could not read the full encrypted payload. Hold the tag steady and try again.",
                )

            is MifarePayloadLengthException ->
                DiagnosticMessage(
                    statusMessage = "The tag does not contain a compatible raw MIFARE Classic encrypted message.",
                    detail = "The raw MIFARE header is damaged or incomplete.",
                )

            else ->
                DiagnosticMessage(
                    statusMessage = "The tag does not contain a compatible raw MIFARE Classic encrypted message.",
                    detail = "The tag does not contain a valid raw MIFARE message for this app.",
                )
        }

    fun ndefPayloadFailure(): DiagnosticMessage =
        DiagnosticMessage(
            statusMessage = "This tag contains an unreadable encrypted payload.",
            detail = "Only this app's encrypted NFC MIME record is supported.",
        )

    fun mifareIoFailure(action: String, error: IOException): DiagnosticMessage =
        ioFailure(
            backend = "MIFARE Classic",
            action = action,
            error = error,
            defaultStatus = "Unable to ${action.lowercase()} the MIFARE Classic tag. Hold it steady and try again.",
        )

    fun ndefIoFailure(action: String, error: IOException): DiagnosticMessage =
        ioFailure(
            backend = "NDEF",
            action = action,
            error = error,
            defaultStatus = "Unable to ${action.lowercase()} the tag. Hold it steady and try again.",
        )

    private fun unsupportedOperation(
        technologies: List<String>,
        statusMessage: String,
        action: String,
    ): DiagnosticMessage {
        val techSummary = technologies.joinToString(", ").ifBlank { "unknown" }
        val detail = when {
            "NfcA" in technologies && "MifareClassic" !in technologies ->
                "Detected technologies: $techSummary. This is an NFC-A tag, but Android did not expose raw MIFARE Classic access on this handset. The tag also was not available through a compatible NDEF path for $action."

            "NdefFormatable" in technologies ->
                "Detected technologies: $techSummary. Android can format this tag for NDEF, but it did not expose a readable/writable NDEF message for the requested operation yet."

            else ->
                "Detected technologies: $techSummary."
        }
        return DiagnosticMessage(statusMessage = statusMessage, detail = detail)
    }

    private fun ioFailure(
        backend: String,
        action: String,
        error: IOException,
        defaultStatus: String,
    ): DiagnosticMessage {
        val message = error.message.orEmpty()
        return when {
            message.contains("tag was lost", ignoreCase = true) ||
                message.contains("transceive failed", ignoreCase = true) ->
                DiagnosticMessage(
                    statusMessage = "The phone lost the tag during $backend access.",
                    detail = "Keep the tag flat against the phone's NFC antenna until the operation finishes.",
                )

            message.contains("lost authentication", ignoreCase = true) ->
                DiagnosticMessage(
                    statusMessage = "The tag stopped authenticating during $backend access.",
                    detail = "Keep the tag steady. If this repeats, the sector keys or access conditions may be unusual.",
                )

            message.contains("connect", ignoreCase = true) ->
                DiagnosticMessage(
                    statusMessage = "The phone could not open the tag for $backend access.",
                    detail = "Some handsets expose only part of a tag's technology stack on a given scan. Try again with the tag held still.",
                )

            else ->
                DiagnosticMessage(
                    statusMessage = defaultStatus,
                    detail = if (message.isBlank()) null else "$backend error while trying to ${action.lowercase()}: $message",
                )
        }
    }
}
