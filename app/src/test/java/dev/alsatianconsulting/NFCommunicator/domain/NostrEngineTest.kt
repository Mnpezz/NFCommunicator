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
}
