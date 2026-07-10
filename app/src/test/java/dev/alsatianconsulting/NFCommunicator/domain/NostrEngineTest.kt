package dev.alsatianconsulting.NFCommunicator.domain

import org.junit.Assert.*
import org.junit.Test

class NostrEngineTest {

    @Test
    fun testDeriveNostrKeys_valid12WordMnemonic() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keys = NostrEngine.deriveNostrKeys(mnemonic)
        
        assertNotNull(keys)
        keys?.let {
            assertTrue(it.nsec.startsWith("nsec1"))
            assertTrue(it.npub.startsWith("npub1"))
            assertEquals(64, it.pubkeyHex.length)
            assertEquals(64, it.privkeyHex.length)
        }
    }

    @Test
    fun testDeriveNostrKeys_invalidMnemonicLength() {
        val mnemonic = "abandon abandon abandon"
        val keys = NostrEngine.deriveNostrKeys(mnemonic)
        assertNull(keys)
    }

    @Test
    fun testDeriveNostrKeys_emptyMnemonic() {
        val keys = NostrEngine.deriveNostrKeys("")
        assertNull(keys)
    }

    @Test
    fun testSchnorrSigningAndEcdh() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keys = NostrEngine.deriveNostrKeys(mnemonic)
        assertNotNull(keys)
        keys?.let {
            val privateKey = KeyParser.parsePrivateKey(it.privkeyHex)
            assertNotNull(privateKey)
            privateKey?.let { priv ->
                val dummyMsg = "hello nostr"
                val hash = fr.acinq.bitcoin.Crypto.sha256(dummyMsg.toByteArray())
                val hash32 = fr.acinq.bitcoin.ByteVector32(hash)
                
                // Perform Schnorr signing using Crypto.signSchnorr
                val signature = fr.acinq.bitcoin.Crypto.signSchnorr(hash32, priv, null, null)
                val sigBytes = fr.acinq.bitcoin.ByteVector(signature.toByteArray())
                assertEquals(64, sigBytes.size())
                
                // Verify signature using Crypto.verifySignatureSchnorr
                val xOnlyPub = fr.acinq.bitcoin.XonlyPublicKey(priv.publicKey())
                val isValid = fr.acinq.bitcoin.Crypto.verifySignatureSchnorr(hash32, sigBytes, xOnlyPub)
                assertTrue(isValid)

                // Test PublicKey parsing and ECDH
                val targetPubkeyHex = it.pubkeyHex
                val targetPubBytes = byteArrayOf(0x02.toByte()) + fr.acinq.bitcoin.ByteVector(targetPubkeyHex).toByteArray()
                val parsedPub = fr.acinq.bitcoin.PublicKey(fr.acinq.bitcoin.ByteVector(targetPubBytes))
                val sharedSecret = fr.acinq.bitcoin.Crypto.ecdh(priv, parsedPub)
                assertEquals(32, sharedSecret.size)
            }
        }
    }

    @Test
    fun testEventSerializationAndSigning() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keys = NostrEngine.deriveNostrKeys(mnemonic)
        assertNotNull(keys)
        
        val eventJson = """{"kind":1,"created_at":1672531200,"content":"Hello Nostr!","tags":[["p","cfbcd567"]]}"""
        val (sig, signedEvent) = NostrEngine.signEvent(eventJson, keys!!.privkeyHex)
        
        assertNotNull(sig)
        assertNotNull(signedEvent)
        assertTrue(signedEvent.contains("\"sig\":\"$sig\""))
        assertTrue(signedEvent.contains("\"id\":"))
    }

    @Test
    fun testNip04EncryptDecrypt() {
        val mnemonicA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keysA = NostrEngine.deriveNostrKeys(mnemonicA)
        assertNotNull(keysA)
        
        val mnemonicB = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        val keysB = NostrEngine.deriveNostrKeys(mnemonicB)
        assertNotNull(keysB)
        
        val privA = KeyParser.parsePrivateKey(keysA!!.privkeyHex)!!
        val targetPubB = byteArrayOf(0x02.toByte()) + fr.acinq.bitcoin.ByteVector(keysB!!.pubkeyHex).toByteArray()
        val pubB = fr.acinq.bitcoin.PublicKey(fr.acinq.bitcoin.ByteVector(targetPubB))

        val privB = KeyParser.parsePrivateKey(keysB.privkeyHex)!!
        val targetPubA = byteArrayOf(0x02.toByte()) + fr.acinq.bitcoin.ByteVector(keysA.pubkeyHex).toByteArray()
        val pubA = fr.acinq.bitcoin.PublicKey(fr.acinq.bitcoin.ByteVector(targetPubA))

        // Derive shared secrets using PublicKey.times(PrivateKey)
        val pointA = pubB.times(privA)
        val pointB = pubA.times(privB)

        val xA = pointA.value.toByteArray().sliceArray(1..32)
        val xB = pointB.value.toByteArray().sliceArray(1..32)

        assertArrayEquals(xA, xB)

        val plaintext = "Secret message between Alice and Bob."
        val encrypted = NostrEngine.nip04Encrypt(plaintext, keysB.pubkeyHex, keysA.privkeyHex)
        assertTrue(encrypted.contains("?iv="))
        
        val decrypted = NostrEngine.nip04Decrypt(encrypted, keysA.pubkeyHex, keysB.privkeyHex)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testNip44EncryptDecrypt() {
        val mnemonicA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keysA = NostrEngine.deriveNostrKeys(mnemonicA)
        assertNotNull(keysA)
        
        val mnemonicB = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        val keysB = NostrEngine.deriveNostrKeys(mnemonicB)
        assertNotNull(keysB)

        val plaintext = "Hello this is a secure NIP-44 message with variable padding."
        
        // Encrypt A -> B
        val encrypted = NostrEngine.nip44Encrypt(plaintext, keysB!!.pubkeyHex, keysA!!.privkeyHex)
        
        // Decrypt B -> A
        val decrypted = NostrEngine.nip44Decrypt(encrypted, keysA.pubkeyHex, keysB.privkeyHex)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testNormalizeNostrPubKey() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keys = NostrEngine.deriveNostrKeys(mnemonic)!!
        val hex = keys.pubkeyHex
        val npub = keys.npub

        assertEquals(hex, NostrEngine.normalizeNostrPubKey("  $hex  "))
        assertEquals(hex, NostrEngine.normalizeNostrPubKey(hex.uppercase()))
        assertEquals(hex, NostrEngine.normalizeNostrPubKey(npub))
        assertEquals(hex, NostrEngine.normalizeNostrPubKey(npub.uppercase()))
        assertEquals(hex, NostrEngine.normalizeNostrPubKey("   $npub   "))
        
        assertNull(NostrEngine.normalizeNostrPubKey(null))
        assertNull(NostrEngine.normalizeNostrPubKey("   "))
    }

    @Test
    fun testNip04Decrypt_explicitIv() {
        val mnemonicA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keysA = NostrEngine.deriveNostrKeys(mnemonicA)!!
        val keysB = NostrEngine.deriveNostrKeys("legal winner thank year wave sausage worth useful legal winner thank yellow")!!

        val plaintext = "Explicit IV decryption works!"
        val encryptedWithIv = NostrEngine.nip04Encrypt(plaintext, keysB.pubkeyHex, keysA.privkeyHex)
        
        val parts = encryptedWithIv.split("?iv=")
        assertEquals(2, parts.size)
        val ciphertext = parts[0]
        val iv = parts[1]

        // Decrypt using explicit IV
        val decrypted = NostrEngine.nip04Decrypt(ciphertext, keysA.pubkeyHex, keysB.privkeyHex, explicitIvBase64 = iv)
        assertEquals(plaintext, decrypted)

        // Invalid cases
        try {
            NostrEngine.nip04Decrypt(ciphertext, keysA.pubkeyHex, keysB.privkeyHex, explicitIvBase64 = "")
            fail("Should have failed for empty IV")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            NostrEngine.nip04Decrypt(ciphertext, keysA.pubkeyHex, keysB.privkeyHex, explicitIvBase64 = null)
            fail("Should have failed for null IV without ?iv=")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun testDecodeIvString_validFormats() {
        val ivBytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        
        // 1. Hex format
        val hexStr = "000102030405060708090a0b0c0d0e0f"
        assertArrayEquals(ivBytes, NostrEngine.decodeIvString(hexStr))
        assertArrayEquals(ivBytes, NostrEngine.decodeIvString(hexStr.uppercase()))
        
        // 2. Base64 format
        val b64Str = java.util.Base64.getEncoder().encodeToString(ivBytes)
        assertArrayEquals(ivBytes, NostrEngine.decodeIvString(b64Str))

        // 3. URL-safe Base64 format
        val urlSafeStr = b64Str.replace('+', '-').replace('/', '_')
        assertArrayEquals(ivBytes, NostrEngine.decodeIvString(urlSafeStr))
    }

    @Test
    fun testDecodeIvString_invalidSize() {
        // Hex with 12 bytes instead of 16
        val shortHex = "000102030405060708090a0b"
        val decoded = NostrEngine.decodeIvString(shortHex)
        assertNotEquals(16, decoded.size)
    }

    @Test
    fun testNip04Decrypt_hexAndUrlSafeIv() {
        val mnemonicA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keysA = NostrEngine.deriveNostrKeys(mnemonicA)!!
        val keysB = NostrEngine.deriveNostrKeys("legal winner thank year wave sausage worth useful legal winner thank yellow")!!

        val plaintext = "Decrypt with hex/url-safe IV works!"
        val encryptedWithIv = NostrEngine.nip04Encrypt(plaintext, keysB.pubkeyHex, keysA.privkeyHex)
        
        val parts = encryptedWithIv.split("?iv=")
        val ciphertext = parts[0]
        val ivBase64 = parts[1]

        val ivBytes = java.util.Base64.getDecoder().decode(ivBase64)
        val ivHex = fr.acinq.bitcoin.ByteVector(ivBytes).toHex()
        val ivUrlSafe = ivBase64.replace('+', '-').replace('/', '_')

        // 1. Decrypt with Hex IV
        val decHex = NostrEngine.nip04Decrypt(ciphertext, keysA.pubkeyHex, keysB.privkeyHex, explicitIvBase64 = ivHex)
        assertEquals(plaintext, decHex)

        // 2. Decrypt with URL-safe IV
        val decUrlSafe = NostrEngine.nip04Decrypt(ciphertext, keysA.pubkeyHex, keysB.privkeyHex, explicitIvBase64 = ivUrlSafe)
        assertEquals(plaintext, decUrlSafe)
    }
}
