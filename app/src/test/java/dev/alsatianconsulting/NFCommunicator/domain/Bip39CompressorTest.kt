/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
package dev.alsatianconsulting.NFCommunicator.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode

class Bip39CompressorTest {
    @Test
    fun roundTrip12Words() {
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val entropy = Bip39Compressor.mnemonicToEntropy(mnemonic)
        assertEquals(16, entropy.size)
        // Verify all 0s for "abandon ... about"
        assertArrayEquals(ByteArray(16), entropy)

        val restored = Bip39Compressor.entropyToMnemonic(entropy)
        assertEquals(mnemonic, restored)
    }

    @Test
    fun roundTrip24Words() {
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "art"
        )
        val entropy = Bip39Compressor.mnemonicToEntropy(mnemonic)
        assertEquals(32, entropy.size)
        assertArrayEquals(ByteArray(32), entropy)

        val restored = Bip39Compressor.entropyToMnemonic(entropy)
        assertEquals(mnemonic, restored)
    }

    @Test
    fun testAddressDerivation() {
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val master = DeterministicWallet.generate(seed)

        val derived = DeterministicWallet.derivePrivateKey(master, "m/84'/0'/0'/0/0")
        val pubkey = derived.publicKey
        val address = pubkey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)

        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", address)
    }

    @Test
    fun testCleanAndSplitMnemonic() {
        val messyMnemonic = "1. abandon, 2. abandon; \n3. abandon \t 4. abandon 5. abandon 6. abandon 7. abandon 8. abandon 9. abandon 10. abandon 11. abandon 12. about"
        val expected = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val cleaned = Bip39Compressor.cleanAndSplitMnemonic(messyMnemonic)
        assertEquals(expected, cleaned)
    }

}
