/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
package dev.alsatianconsulting.NFCommunicator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NfcDiagnosticsTest {
    @Test
    fun unexpectedFailureUsesThrowableMessage() {
        val diagnostic = NfcDiagnostics.unexpectedFailure("write", IllegalStateException("boom"))

        assertEquals("Unexpected error while trying to write the tag.", diagnostic.statusMessage)
        assertEquals("boom", diagnostic.detail)
    }

    @Test
    fun ndefNoMessageFoundForEmptyTagMentionsNoRecords() {
        val diagnostic = NfcDiagnostics.ndefNoMessageFound(isEmpty = true)

        assertEquals("No encrypted message from this app was found on the tag.", diagnostic.statusMessage)
        assertTrue(diagnostic.detail.orEmpty().contains("did not contain any NDEF records"))
    }

    @Test
    fun unsupportedReadMentionsMissingRawMifareExposureForNfcA() {
        val diagnostic = NfcDiagnostics.unsupportedRead(listOf("NfcA"))

        assertEquals("This tag cannot be read on this phone in the selected mode.", diagnostic.statusMessage)
        assertTrue(diagnostic.detail.orEmpty().contains("did not expose raw MIFARE Classic access"))
    }

    @Test
    fun mifareAuthenticationFailureMentionsCommonKeys() {
        val diagnostic = NfcDiagnostics.mifareAuthenticationFailure()

        assertTrue(diagnostic.detail.orEmpty().contains("default, NFC Forum, and MAD keys"))
    }

    @Test
    fun mifareIoFailureClassifiesLostTag() {
        val diagnostic = NfcDiagnostics.mifareIoFailure("read", IOException("Tag was lost."))

        assertEquals("The phone lost the tag during MIFARE Classic access.", diagnostic.statusMessage)
    }

    @Test
    fun mifarePayloadFailureExplainsTruncation() {
        val diagnostic = NfcDiagnostics.mifarePayloadFailure(
            MifarePayloadTruncatedException("The MIFARE Classic payload is truncated."),
        )

        assertTrue(diagnostic.detail.orEmpty().contains("could not read the full encrypted payload"))
    }

    @Test
    fun mifarePayloadFailureWithoutAppFormatUsesNoMessageStatus() {
        val diagnostic = NfcDiagnostics.mifarePayloadFailure(
            MifareHeaderNotFoundException(
                "The MIFARE Classic payload is not in this app's format.",
                isTooShort = false,
            ),
        )

        assertEquals("No encrypted message from this app was found on the tag.", diagnostic.statusMessage)
    }

    @Test
    fun mifarePayloadFailureForTooShortHeaderUsesNoMessageStatus() {
        val diagnostic = NfcDiagnostics.mifarePayloadFailure(
            MifareHeaderNotFoundException(
                "The MIFARE Classic payload is too short.",
                isTooShort = true,
            ),
        )

        assertEquals("No encrypted message from this app was found on the tag.", diagnostic.statusMessage)
        assertTrue(diagnostic.detail.orEmpty().contains("enough data"))
    }

    @Test
    fun mifarePayloadFailureForInvalidLengthUsesCompatibleStatus() {
        val diagnostic = NfcDiagnostics.mifarePayloadFailure(
            MifarePayloadLengthException("The MIFARE Classic payload length is invalid."),
        )

        assertEquals(
            "The tag does not contain a compatible raw MIFARE Classic encrypted message.",
            diagnostic.statusMessage,
        )
        assertTrue(diagnostic.detail.orEmpty().contains("damaged"))
    }
}
