package dev.alsatianconsulting.NFCommunicator.domain

import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import org.junit.Assert.*
import org.junit.Test

class KeyParserTest {

    @Test
    fun testParseWifCompressed() {
        val rawKeyBytes = ByteArray(32) { 1.toByte() }
        val payload = ByteArray(33)
        System.arraycopy(rawKeyBytes, 0, payload, 0, 32)
        payload[32] = 1.toByte() // compressed flag
        val wif = Base58Check.encode(0x80.toByte(), payload)

        val parsed = KeyParser.parsePrivateKey(wif)
        assertNotNull(parsed)
        assertEquals(rawKeyBytes.joinToString("") { "%02x".format(it) }, parsed!!.value.toHex())
    }

    @Test
    fun testParseWifUncompressed() {
        val rawKeyBytes = ByteArray(32) { 2.toByte() }
        val wif = Base58Check.encode(0x80.toByte(), rawKeyBytes)

        val parsed = KeyParser.parsePrivateKey(wif)
        assertNotNull(parsed)
        assertEquals(rawKeyBytes.joinToString("") { "%02x".format(it) }, parsed!!.value.toHex())
    }

    @Test
    fun testParseHex() {
        val hex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d0e0f10"
        val parsed = KeyParser.parsePrivateKey(hex)
        assertNotNull(parsed)
        assertEquals(hex, parsed!!.value.toHex())
    }

    @Test
    fun testParseExtended() {
        val seed = ByteArray(32) { 3.toByte() }
        val master = DeterministicWallet.generate(seed)
        val serialized = master.encode(false)

        val parsed = KeyParser.parsePrivateKey(serialized)
        assertNotNull(parsed)
        // Should match the derived BIP-84 private key
        val expected = master.derivePrivateKey("m/84'/0'/0'/0/0").privateKey
        assertEquals(expected.value.toHex(), parsed!!.value.toHex())
    }

    @Test
    fun testParseInvalid() {
        assertNull(KeyParser.parsePrivateKey(""))
        assertNull(KeyParser.parsePrivateKey("invalidKey"))
        assertNull(KeyParser.parsePrivateKey("010203")) // too short hex
    }
}
