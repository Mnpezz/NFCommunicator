package dev.alsatianconsulting.NFCommunicator.domain

import dev.alsatianconsulting.NFCommunicator.data.Utxo
import fr.acinq.bitcoin.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SilentPaymentsTest {

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        for (i in data.indices) {
            val value = data[i].toInt() and 0xff
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.write((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) {
                out.write((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Could not convert bits")
        }
        return out.toByteArray()
    }

    private fun encodeSilentPaymentAddress(hrp: String, scanPubKey: ByteArray, spendPubKey: ByteArray): String {
        val payload = ByteArray(66)
        System.arraycopy(scanPubKey, 0, payload, 0, 33)
        System.arraycopy(spendPubKey, 0, payload, 33, 33)
        val payload5Bit = convertBits(payload, 8, 5, true)
        val data5Bit = ByteArray(payload5Bit.size + 1)
        data5Bit[0] = 0 // version 0
        System.arraycopy(payload5Bit, 0, data5Bit, 1, payload5Bit.size)
        return Bech32.encode(hrp, data5Bit.toTypedArray(), Bech32.Encoding.Bech32m)
    }

    @Test
    fun testIsSilentPaymentAddress() {
        assertTrue(WalletEngine.isSilentPaymentAddress("sp1qg8nuaqgp5w673f4vgh52w3u3xzk39f323f4vgh52w3u3xzk39f323f4vgh52w3u3xzk39f323q"))
        assertTrue(WalletEngine.isSilentPaymentAddress("tsp1qg8nuaqgp5w673f4vgh52w3u3xzk39f323f4vgh52w3u3xzk39f323f4vgh52w3u3xzk39f323q"))
        assertFalse(WalletEngine.isSilentPaymentAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertFalse(WalletEngine.isSilentPaymentAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
    }

    @Test
    fun testEncodeDecodeSilentPaymentAddress() {
        val scanPubKey = ByteArray(33) { 1.toByte() }
        val spendPubKey = ByteArray(33) { 2.toByte() }
        
        val address = encodeSilentPaymentAddress("sp", scanPubKey, spendPubKey)
        assertTrue(address.startsWith("sp1"))

        val (decodedScan, decodedSpend) = WalletEngine.decodeSilentPaymentAddress(address)
        assertArrayEquals(scanPubKey, decodedScan)
        assertArrayEquals(spendPubKey, decodedSpend)
    }

    @Test
    fun testBuildAndSignSilentPaymentTransaction() {
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val master = DeterministicWallet.generate(seed)
        val derived = DeterministicWallet.derivePrivateKey(master, WalletEngine.BIP84_DERIVATION_PATH)
        val privKey = derived.privateKey
        val activeAddress = derived.publicKey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)

        // Setup dummy UTXOs
        val utxos = listOf(
            Utxo(
                txid = "0000000000000000000000000000000000000000000000000000000000000001",
                vout = 0,
                value = 100000L,
                confirmed = true
            )
        )

        // Create a destination silent payment address
        // Using the public key of the derived key as both scan and spend keys for testing
        val destPubKeyBytes = derived.publicKey.value.toByteArray()
        val destSpAddress = encodeSilentPaymentAddress("sp", destPubKeyBytes, destPubKeyBytes)

        val signedTxHex = WalletEngine.buildAndSignSilentPaymentTransaction(
            utxos = utxos,
            silentPaymentAddress = destSpAddress,
            amountSats = 50000L,
            changeAddress = activeAddress,
            feeRateSatsPerVByte = 2L,
            privateKey = privKey
        )

        assertTrue(signedTxHex.isNotEmpty())
        assertTrue(signedTxHex.length >= 400)
    }
}
