package dev.alsatianconsulting.NFCommunicator.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageCodecTest {
    @Test
    fun encryptAndDecryptRoundTrip() {
        val password = "shared-secret"
        val message = "Hello from an NFC card."

        val payload = SecureMessageCodec.encryptToPayload(message, password)
        val decrypted = SecureMessageCodec.decryptPayload(payload, password)

        assertEquals(message, decrypted)
    }

    @Test(expected = InvalidPasswordException::class)
    fun wrongPasswordFailsDecryption() {
        val payload = SecureMessageCodec.encryptToPayload("secret", "correct-password")
        SecureMessageCodec.decryptPayload(payload, "wrong-password")
    }

    @Test
    fun ndefEstimateIncludesEncryptionOverhead() {
        val estimated = SecureMessageCodec.estimateNdefMessageSize("abc")
        val payloadOnly = SecureMessageCodec.estimateEncryptedPayloadSize("abc")

        assertTrue(estimated > payloadOnly)
    }

    @Test
    fun mifareEnvelopeRoundTrip() {
        val encryptedPayload = SecureMessageCodec.encryptToPayload("secret", "shared-password")
        val wrapped = SecureMessageCodec.wrapForMifareClassic(encryptedPayload)

        val extracted = SecureMessageCodec.extractFromMifareClassic(wrapped)

        assertArrayEquals(encryptedPayload, extracted)
    }

    @Test
    fun mifareEstimateIncludesEnvelope() {
        val estimated = SecureMessageCodec.estimateMifareClassicStorageSize("abc")
        val payloadOnly = SecureMessageCodec.estimateEncryptedPayloadSize("abc")

        assertTrue(estimated > payloadOnly)
    }

    @Test
    fun mifareHeaderReportsRequiredStorageSize() {
        val encryptedPayload = SecureMessageCodec.encryptToPayload("secret", "shared-password")
        val wrapped = SecureMessageCodec.wrapForMifareClassic(encryptedPayload)

        val requiredBytes = SecureMessageCodec.requiredMifareClassicStorageSize(wrapped.copyOf(16))

        assertEquals(wrapped.size, requiredBytes)
    }

    @Test(expected = InvalidPayloadException::class)
    fun truncatedMifareEnvelopeFailsExtraction() {
        val encryptedPayload = SecureMessageCodec.encryptToPayload("secret", "shared-password")
        val wrapped = SecureMessageCodec.wrapForMifareClassic(encryptedPayload)

        SecureMessageCodec.extractFromMifareClassic(wrapped.copyOf(wrapped.size - 1))
    }

    @Test
    fun ndefMaxPlainTextCharactersMatchesCapacity() {
        val message = "abc"
        val capacity = SecureMessageCodec.estimateNdefMessageSize(message)

        val maxCharacters = SecureMessageCodec.maxPlainTextCharactersForNdef(capacity)

        assertEquals(message.length, maxCharacters)
    }

    @Test
    fun mifareMaxPlainTextCharactersMatchesCapacity() {
        val message = "abc"
        val capacity = SecureMessageCodec.estimateMifareClassicStorageSize(message)

        val maxCharacters = SecureMessageCodec.maxPlainTextCharactersForMifareClassic(capacity)

        assertEquals(message.length, maxCharacters)
    }

    @Test
    fun encryptAndDecryptEntropyRoundTrip() {
        val password = "bitcoin-vault-password"
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val entropy = Bip39Compressor.mnemonicToEntropy(mnemonic)
        val payload = SecureMessageCodec.encryptEntropyToPayload(entropy, password)
        val decrypted = SecureMessageCodec.decryptPayload(payload, password)

        assertEquals(mnemonic.joinToString(" "), decrypted)
    }

    @Test
    fun duressWalletRoundTrip() {
        val mainPassword = "main-secret-password"
        val mainMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val duressPassword = "duress-secret-password"
        val duressMnemonic = "about about about about about about about about about about about about"

        val payload = SecureMessageCodec.encryptDuressToPayload(
            mainPlainText = mainMnemonic,
            mainPassword = mainPassword,
            duressPlainText = duressMnemonic,
            duressPassword = duressPassword
        )

        // Decrypting with mainPassword returns mainMnemonic
        val decryptedMain = SecureMessageCodec.decryptPayload(payload, mainPassword)
        assertEquals(mainMnemonic, decryptedMain)

        // Decrypting with duressPassword returns duressMnemonic
        val decryptedDuress = SecureMessageCodec.decryptPayload(payload, duressPassword)
        assertEquals(duressMnemonic, decryptedDuress)
    }

    @Test(expected = InvalidPasswordException::class)
    fun duressWrongPasswordThrows() {
        val payload = SecureMessageCodec.encryptDuressToPayload(
            mainPlainText = "main message",
            mainPassword = "main-password",
            duressPlainText = "duress message",
            duressPassword = "duress-password"
        )
        SecureMessageCodec.decryptPayload(payload, "incorrect-password")
    }
}
