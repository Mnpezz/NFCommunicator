/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
package dev.alsatianconsulting.NFCommunicator.domain

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecureMessageCodec {
    private const val formatVersion: Byte = 1
    private const val keyLengthBits = 256
    private const val gcmTagLengthBits = 128
    private const val gcmTagLengthBytes = gcmTagLengthBits / 8
    private const val saltLengthBytes = 16
    private const val nonceLengthBytes = 12
    // OWASP 2023 / NIST SP 800-132: minimum 600 000 iterations for PBKDF2-HMAC-SHA256.
    private const val pbkdf2Iterations = 600_000
    private val secureRandom = SecureRandom()
    // The AAD literal contains an extra 'C' — "NFCCommunicator" instead of "NFCommunicator" —
    // and is intentionally kept as-is for on-tag format compatibility; changing it would make
    // all existing encrypted tags unreadable.
    private val associatedData = "NFCCommunicator|v1".toByteArray(StandardCharsets.UTF_8)
    // Shortened MIME type (app/nc) saves 40 bytes of NDEF overhead on small tags.
    val mimeType: String = "app/nc"
    private val mimeTypeBytes = mimeType.toByteArray(StandardCharsets.US_ASCII)
    private const val legacyMimeType = "application/vnd.nfccommunicator.secure-message"
    private val legacyMimeTypeBytes = legacyMimeType.toByteArray(StandardCharsets.US_ASCII)
    private val mifareMagic = "MFCMSG01".toByteArray(StandardCharsets.US_ASCII)
    private const val mifareHeaderLengthBytes = 12
    // Maximum plausible MIFARE Classic payload: 4 KB total – header. Rejects attacker-crafted
    // payloadLength fields that would overflow Int arithmetic (CWE-190).
    private const val maxMifarePayloadBytes = 4_096 - mifareHeaderLengthBytes

    fun encryptToPayload(plainText: String, password: String): ByteArray {
        val messageBytes = plainText.toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(saltLengthBytes).also(secureRandom::nextBytes)
        val nonce = ByteArray(nonceLengthBytes).also(secureRandom::nextBytes)
        val key = deriveKey(password, salt)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(gcmTagLengthBits, nonce))
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(messageBytes)

            ByteBuffer.allocate(1 + saltLengthBytes + nonceLengthBytes + ciphertext.size)
                .put(formatVersion)
                .put(salt)
                .put(nonce)
                .put(ciphertext)
                .array()
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Unable to encrypt the NFC message.", error)
        }
    }

    fun decryptPayload(payload: ByteArray, password: String): String {
        if (payload.size < 1 + saltLengthBytes + nonceLengthBytes + gcmTagLengthBytes) {
            throw InvalidPayloadException("The NFC payload is too short.")
        }

        val buffer = ByteBuffer.wrap(payload)
        val version = buffer.get()
        if (version != formatVersion) {
            throw InvalidPayloadException("Unsupported NFC payload version.")
        }

        val salt = ByteArray(saltLengthBytes)
        buffer.get(salt)
        val nonce = ByteArray(nonceLengthBytes)
        buffer.get(nonce)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        if (ciphertext.size < gcmTagLengthBytes) {
            throw InvalidPayloadException("The NFC payload is truncated.")
        }

        val key = deriveKey(password, salt)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(gcmTagLengthBits, nonce))
            cipher.updateAAD(associatedData)
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (error: AEADBadTagException) {
            throw InvalidPasswordException(error)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Unable to decrypt the NFC message.", error)
        }
    }

    fun createNdefMessage(payload: ByteArray): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createMime(mimeType, payload)))

    fun createEmptyNdefMessage(): NdefMessage {
        val emptyRecord = NdefRecord(
            NdefRecord.TNF_EMPTY,
            ByteArray(0),
            ByteArray(0),
            ByteArray(0),
        )
        return NdefMessage(arrayOf(emptyRecord))
    }

    fun isCompatibleRecord(record: NdefRecord): Boolean =
        record.tnf.toInt() == NdefRecord.TNF_MIME_MEDIA.toInt() &&
            (record.type.contentEquals(mimeTypeBytes) || record.type.contentEquals(legacyMimeTypeBytes))

    fun wrapForMifareClassic(encryptedPayload: ByteArray): ByteArray =
        ByteBuffer.allocate(mifareHeaderLengthBytes + encryptedPayload.size)
            .put(mifareMagic)
            .putInt(encryptedPayload.size)
            .put(encryptedPayload)
            .array()

    fun requiredMifareClassicStorageSize(rawBytes: ByteArray): Int {
        if (rawBytes.size < mifareHeaderLengthBytes) {
            throw MifareHeaderNotFoundException(
                "The MIFARE Classic payload is too short.",
                isTooShort = true,
            )
        }

        val buffer = ByteBuffer.wrap(rawBytes)
        val magic = ByteArray(mifareMagic.size)
        buffer.get(magic)
        if (!magic.contentEquals(mifareMagic)) {
            throw MifareHeaderNotFoundException(
                "The MIFARE Classic payload is not in this app's format.",
                isTooShort = false,
            )
        }

        val payloadLength = buffer.int
        if (payloadLength <= 0 || payloadLength > maxMifarePayloadBytes) {
            throw MifarePayloadLengthException("The MIFARE Classic payload length is invalid.")
        }

        return mifareHeaderLengthBytes + payloadLength
    }

    fun extractFromMifareClassic(rawBytes: ByteArray): ByteArray {
        val requiredBytes = requiredMifareClassicStorageSize(rawBytes)
        if (requiredBytes > rawBytes.size) {
            throw MifarePayloadTruncatedException("The MIFARE Classic payload is truncated.")
        }

        return rawBytes.copyOfRange(mifareHeaderLengthBytes, requiredBytes)
    }

    fun estimateEncryptedPayloadSize(plainText: String): Int {
        val plainTextBytes = plainText.toByteArray(StandardCharsets.UTF_8).size
        return estimateEncryptedPayloadSize(plainTextBytes)
    }

    fun estimateNdefMessageSize(plainText: String): Int {
        val payloadSize = estimateEncryptedPayloadSize(plainText)
        val payloadLengthFieldSize = if (payloadSize < 0x100) 1 else 4
        return 1 + 1 + payloadLengthFieldSize + mimeTypeBytes.size + payloadSize
    }

    fun estimateMifareClassicStorageSize(plainText: String): Int =
        mifareHeaderLengthBytes + estimateEncryptedPayloadSize(plainText)

    fun maxPlainTextCharactersForNdef(capacityBytes: Int): Int {
        if (capacityBytes <= 0) {
            return 0
        }

        var low = 0
        var high = capacityBytes
        while (low < high) {
            val midpointCharacters = (low + high + 1) / 2
            if (estimateNdefMessageSizeForCharacterCount(midpointCharacters) <= capacityBytes) {
                low = midpointCharacters
            } else {
                high = midpointCharacters - 1
            }
        }
        return low
    }

    fun maxPlainTextCharactersForMifareClassic(capacityBytes: Int): Int =
        (capacityBytes - mifareHeaderLengthBytes - encryptionOverheadBytes).coerceAtLeast(0)

    private fun estimateEncryptedPayloadSize(plainTextBytes: Int): Int =
        encryptionOverheadBytes + plainTextBytes

    private fun estimateNdefMessageSizeForCharacterCount(characterCount: Int): Int {
        // Assumes 1 byte per character (ASCII). This over-reports available capacity for
        // multi-byte UTF-8 content (e.g. CJK, emoji). The hard character cap is therefore
        // not applied in the UI — the exact fit is verified with the real UTF-8 byte count
        // when the user actually writes the tag.
        val payloadSize = estimateEncryptedPayloadSize(characterCount)
        val payloadLengthFieldSize = if (payloadSize < 0x100) 1 else 4
        return 1 + 1 + payloadLengthFieldSize + mimeTypeBytes.size + payloadSize
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        // NIST SP 800-57 §8.1: zeroize key material as soon as it is no longer needed.
        // PBEKeySpec holds an internal char[] copy of the password that can be explicitly zeroed.
        val keySpec = PBEKeySpec(password.toCharArray(), salt, pbkdf2Iterations, keyLengthBits)
        return try {
            val secretFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(secretFactory.generateSecret(keySpec).encoded, "AES")
        } finally {
            keySpec.clearPassword()
        }
    }

    private val encryptionOverheadBytes: Int =
        1 + saltLengthBytes + nonceLengthBytes + gcmTagLengthBytes
}

open class InvalidPayloadException(message: String) : IllegalArgumentException(message)

/**
 * The MIFARE header was not found: either the raw data is shorter than the 12-byte header
 * ([isTooShort] == true) or the magic bytes did not match this app's format ([isTooShort] == false).
 */
class MifareHeaderNotFoundException(
    message: String,
    val isTooShort: Boolean,
) : InvalidPayloadException(message)

/** The MIFARE payload length field contains an out-of-range value. */
class MifarePayloadLengthException(message: String) : InvalidPayloadException(message)

/** The MIFARE header was valid but the full payload bytes were not available. */
class MifarePayloadTruncatedException(message: String) : InvalidPayloadException(message)

class InvalidPasswordException(cause: Throwable) : SecurityException("Wrong password or corrupted data.", cause)
