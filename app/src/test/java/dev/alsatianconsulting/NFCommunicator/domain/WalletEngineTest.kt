package dev.alsatianconsulting.NFCommunicator.domain

import dev.alsatianconsulting.NFCommunicator.data.Utxo
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.Bech32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WalletEngineTest {

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
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
    fun testDecodeBech32Address() {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val (hrp, version, program) = WalletEngine.decodeBech32Address(address)
        assertEquals("bc", hrp)
        assertEquals(0, version)
        assertEquals(20, program.size)
    }

    @Test
    fun testAddressToScriptPubKey() {
        val address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val scriptPubKey = WalletEngine.addressToScriptPubKey(address)
        // P2WPKH should be 0x00 0x14 <20 bytes>
        assertEquals(22, scriptPubKey.size)
        assertEquals(0x00.toByte(), scriptPubKey[0])
        assertEquals(0x14.toByte(), scriptPubKey[1])
    }

    @Test
    fun testBuildAndSignTransaction() {
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val master = DeterministicWallet.generate(seed)
        val derived = DeterministicWallet.derivePrivateKey(master, WalletEngine.BIP84_DERIVATION_PATH)
        val privKey = derived.privateKey
        
        val activeAddress = derived.publicKey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", activeAddress)

        // Setup dummy UTXO
        val utxos = listOf(
            Utxo(
                txid = "0000000000000000000000000000000000000000000000000000000000000001",
                vout = 0,
                value = 100000L, // 100k sats
                confirmed = true
            )
        )

        // Build and sign
        val toAddress = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" // Use valid Bech32 address
        val amount = 50000L
        val changeAddress = activeAddress
        val feeRate = 2L // 2 sat/vB

        val signedTxHex = WalletEngine.buildAndSignTransaction(
            utxos = utxos,
            toAddress = toAddress,
            amountSats = amount,
            changeAddress = changeAddress,
            feeRateSatsPerVByte = feeRate,
            privateKey = privKey
        )

        assertTrue(signedTxHex.isNotEmpty())
        // A standard 1-input 2-output SegWit transaction should be around 220-225 bytes (440-450 characters hex)
        assertTrue(signedTxHex.length >= 400)
    }

    @Test
    fun testBuildAndSignTransactionWithMultiIndex() {
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val master = DeterministicWallet.generate(seed)
        
        // Derive keys for indices 0 and 1
        val derived0 = DeterministicWallet.derivePrivateKey(master, "m/84'/0'/0'/0/0")
        val derived1 = DeterministicWallet.derivePrivateKey(master, "m/84'/0'/0'/0/1")
        
        val addr0 = derived0.publicKey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)
        val addr1 = derived1.publicKey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)

        val privateKeys = mapOf(
            0 to derived0.privateKey,
            1 to derived1.privateKey
        )

        // Setup dummy UTXOs
        val utxos = listOf(
            Utxo(
                txid = "0000000000000000000000000000000000000000000000000000000000000001",
                vout = 0,
                value = 100000L, // 100k sats
                confirmed = true,
                addressIndex = 0,
                address = addr0
            ),
            Utxo(
                txid = "0000000000000000000000000000000000000000000000000000000000000002",
                vout = 1,
                value = 150000L, // 150k sats
                confirmed = true,
                addressIndex = 1,
                address = addr1
            )
        )

        val toAddress = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        val amount = 180000L
        val changeAddress = addr0
        val feeRate = 2L

        val signedTxHex = WalletEngine.buildAndSignTransaction(
            utxos = utxos,
            toAddress = toAddress,
            amountSats = amount,
            changeAddress = changeAddress,
            feeRateSatsPerVByte = feeRate,
            privateKey = derived0.privateKey,
            privateKeys = privateKeys
        )

        assertTrue(signedTxHex.isNotEmpty())
        assertTrue(signedTxHex.length >= 600)
    }

    @Test
    fun testBuildAndSignSilentPaymentTransactionWithMultiIndex() {
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val master = DeterministicWallet.generate(seed)
        
        // Derive keys for indices 0 and 1
        val derived0 = DeterministicWallet.derivePrivateKey(master, "m/84'/0'/0'/0/0")
        val derived1 = DeterministicWallet.derivePrivateKey(master, "m/84'/0'/0'/0/1")
        
        val addr0 = derived0.publicKey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)
        val addr1 = derived1.publicKey.p2wpkhAddress(Block.LivenetGenesisBlock.hash)

        val privateKeys = mapOf(
            0 to derived0.privateKey,
            1 to derived1.privateKey
        )

        // Setup dummy UTXOs
        val utxos = listOf(
            Utxo(
                txid = "0000000000000000000000000000000000000000000000000000000000000001",
                vout = 0,
                value = 100000L,
                confirmed = true,
                addressIndex = 0,
                address = addr0
            ),
            Utxo(
                txid = "0000000000000000000000000000000000000000000000000000000000000002",
                vout = 1,
                value = 150000L,
                confirmed = true,
                addressIndex = 1,
                address = addr1
            )
        )

        val destPubKeyBytes = derived0.publicKey.value.toByteArray()
        val spAddress = encodeSilentPaymentAddress("sp", destPubKeyBytes, destPubKeyBytes)
        val amount = 180000L
        val changeAddress = addr0
        val feeRate = 2L

        val signedTxHex = WalletEngine.buildAndSignSilentPaymentTransaction(
            utxos = utxos,
            silentPaymentAddress = spAddress,
            amountSats = amount,
            changeAddress = changeAddress,
            feeRateSatsPerVByte = feeRate,
            privateKey = derived0.privateKey,
            privateKeys = privateKeys
        )

        assertTrue(signedTxHex.isNotEmpty())
        assertTrue(signedTxHex.length >= 600)
    }
}
