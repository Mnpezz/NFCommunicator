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
}
