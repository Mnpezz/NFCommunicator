package dev.alsatianconsulting.NFCommunicator.domain

import dev.alsatianconsulting.NFCommunicator.data.Utxo
import fr.acinq.bitcoin.*
import java.io.ByteArrayOutputStream

object WalletEngine {
    const val BIP84_DERIVATION_PATH = "m/84'/0'/0'/0/0"

    /**
     * Decodes Bech32 P2WPKH addresses to extract the 20-byte pubkey hash (witness program).
     */
    fun decodeBech32Address(address: String): Triple<String, Int, ByteArray> {
        val result = Bech32.decode(address)
        val hrp = result.first
        val data = result.second
        val witnessVersion = data[0].toInt()
        val witnessProgram5Bit = ByteArray(data.size - 1) { i -> data[i + 1] }
        val witnessProgram8Bit = convertBits(witnessProgram5Bit, 5, 8, false)
        return Triple(hrp, witnessVersion, witnessProgram8Bit)
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = ByteArrayOutputStream()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            val v = value.toInt() and 0xff
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.write((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) {
                result.write((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Could not convert bits without padding")
        }
        return result.toByteArray()
    }

    /**
     * Build the scriptPubKey bytes for a destination address.
     */
    fun addressToScriptPubKey(address: String): ByteArray {
        val clean = address.trim()
        return if (clean.startsWith("bc1") || clean.startsWith("tb1")) {
            val (_, version, program) = decodeBech32Address(clean)
            if (version == 0) {
                val scriptBytes = ByteArray(2 + program.size)
                scriptBytes[0] = 0x00.toByte()
                scriptBytes[1] = program.size.toByte()
                System.arraycopy(program, 0, scriptBytes, 2, program.size)
                scriptBytes
            } else {
                throw IllegalArgumentException("Unsupported witness version: $version")
            }
        } else if (clean.startsWith("1") || clean.startsWith("m") || clean.startsWith("n")) {
            val decoded = Base58Check.decode(clean)
            val program = decoded.second
            val scriptBytes = ByteArray(5 + program.size)
            scriptBytes[0] = 0x76.toByte()
            scriptBytes[1] = 0xa9.toByte()
            scriptBytes[2] = program.size.toByte()
            System.arraycopy(program, 0, scriptBytes, 3, program.size)
            scriptBytes[3 + program.size] = 0x88.toByte()
            scriptBytes[4 + program.size] = 0xac.toByte()
            scriptBytes
        } else if (clean.startsWith("3") || clean.startsWith("2")) {
            val decoded = Base58Check.decode(clean)
            val program = decoded.second
            val scriptBytes = ByteArray(3 + program.size)
            scriptBytes[0] = 0xa9.toByte()
            scriptBytes[1] = program.size.toByte()
            System.arraycopy(program, 0, scriptBytes, 2, program.size)
            scriptBytes[2 + program.size] = 0x87.toByte()
            scriptBytes
        } else {
            throw IllegalArgumentException("Unsupported address format: $clean")
        }
    }

    /**
     * ASN.1 DER-encodes a 64-byte compact signature (r and s).
     */
    fun derEncode(sig: ByteVector64): ByteArray {
        val r = sig.take(32).toByteArray()
        val s = sig.takeRight(32).toByteArray()
        
        fun encodeInteger(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size && bytes[start] == 0.toByte()) {
                start++
            }
            val length = bytes.size - start
            if (length == 0) {
                return byteArrayOf(0x02.toByte(), 0x01.toByte(), 0x00.toByte())
            }
            val needsZero = (bytes[start].toInt() and 0x80) != 0
            val payloadSize = length + (if (needsZero) 1 else 0)
            val result = ByteArray(2 + payloadSize)
            result[0] = 0x02.toByte()
            result[1] = payloadSize.toByte()
            if (needsZero) {
                result[2] = 0.toByte()
                System.arraycopy(bytes, start, result, 3, length)
            } else {
                System.arraycopy(bytes, start, result, 2, length)
            }
            return result
        }
        
        val rEncoded = encodeInteger(r)
        val sEncoded = encodeInteger(s)
        val totalLength = rEncoded.size + sEncoded.size
        val der = ByteArray(2 + totalLength)
        der[0] = 0x30.toByte()
        der[1] = totalLength.toByte()
        System.arraycopy(rEncoded, 0, der, 2, rEncoded.size)
        System.arraycopy(sEncoded, 0, der, 2 + rEncoded.size, sEncoded.size)
        return der
    }

    /**
     * Constructs a signed Native SegWit transaction (P2WPKH).
     * @param utxos Selected UTXOs to spend.
     * @param toAddress Recipient's address.
     * @param amountSats Amount to send in Satoshis.
     * @param changeAddress Destination address for change.
     * @param feeRateSatsPerVByte Sat/vB rate for transaction fee calculation.
     * @param privateKey Ephemeral signing private key.
     * @return Fully signed hex transaction.
     */
    fun buildAndSignTransaction(
        utxos: List<Utxo>,
        toAddress: String,
        amountSats: Long,
        changeAddress: String,
        feeRateSatsPerVByte: Long,
        privateKey: PrivateKey,
        privateKeys: Map<Int, PrivateKey> = emptyMap()
    ): String {
        // 1. Map UTXOs to TxIn
        val txIns = utxos.map { utxo ->
            val txId = TxId(utxo.txid)
            val outPoint = OutPoint(txId, utxo.vout.toLong())
            TxIn(outPoint, ByteVector.empty, sequence = 0xffffffffL)
        }

        // 2. Select UTXOs and compute inputs sum
        val totalInputSats = utxos.sumOf { it.value }

        // 3. Compute estimated virtual size for transaction to calculate fee
        // Native SegWit P2WPKH: 
        // Overhead: 10.5 vBytes (Version: 4, Locktime: 4, Marker/Flag: 0.5, InputCount: 1, OutputCount: 1)
        // Each Input: 68 vBytes (Outpoint: 36, Sequence: 4, Witness item count: 1, Signature: 73, Pubkey: 34)
        // Each Output: 31 vBytes (Value: 8, Script length: 1, Script: 22)
        val estimatedVSize = 11 + (txIns.size * 68) + 2 * 31
        val feeSats = estimatedVSize * feeRateSatsPerVByte
        val changeSats = totalInputSats - amountSats - feeSats

        if (changeSats < 0) {
            throw IllegalArgumentException("Insufficient funds. Available: $totalInputSats, Spend: $amountSats, Estimated Fee: $feeSats")
        }

        // 4. Build TxOuts
        val txOuts = mutableListOf<TxOut>()
        
        // Recipient Output
        val recipientScript = addressToScriptPubKey(toAddress)
        txOuts.add(TxOut(Satoshi(amountSats), recipientScript))

        // Change Output (if above dust limit, e.g. 546 satoshis)
        if (changeSats >= 546L) {
            val changeScript = addressToScriptPubKey(changeAddress)
            txOuts.add(TxOut(Satoshi(changeSats), changeScript))
        }

        // 5. Build Unsigned Transaction
        val unsignedTx = Transaction(version = 2L, txIn = txIns, txOut = txOuts, lockTime = 0L)

        // 6. Sign each input
        val signedTxIns = txIns.mapIndexed { index, txIn ->
            val utxo = utxos[index]
            val inputPrivKey = privateKeys[utxo.addressIndex] ?: privateKey
            val inputPubkey = inputPrivKey.publicKey()
            val scriptCode = Script.pay2pkh(inputPubkey) // P2WPKH scriptCode is P2PKH script of pubkey
            
            // Calculate SegWit signature hash
            val hash = unsignedTx.hashForSigning(
                inputIndex = index,
                previousOutputScript = scriptCode,
                sighashType = 1, // SIGHASH_ALL
                amount = Satoshi(utxo.value),
                signatureVersion = 1 // WITNESS_V0
            )

            // Sign
            val sig = Crypto.sign(ByteVector32(hash), inputPrivKey)
            val derSig = derEncode(sig)
            val signedSig = derSig + byteArrayOf(1) // append SIGHASH_ALL byte

            // Witness consists of [Signature, PublicKey]
            val scriptWitness = ScriptWitness(listOf(
                ByteVector(signedSig),
                inputPubkey.value
            ))

            txIn.copy(witness = scriptWitness)
        }

        // 7. Assemble Signed Transaction
        val signedTx = unsignedTx.copy(txIn = signedTxIns)
        return ByteVector(Transaction.write(signedTx)).toHex()
    }

    fun isSilentPaymentAddress(address: String): Boolean {
        val clean = address.trim()
        return clean.startsWith("sp1", ignoreCase = true) || clean.startsWith("tsp1", ignoreCase = true)
    }

    fun decodeSilentPaymentAddress(address: String): Pair<ByteArray, ByteArray> {
        val clean = address.trim().lowercase()
        val result = Bech32.decode(clean)
        val hrp = result.first
        require(hrp == "sp" || hrp == "tsp") { "Invalid silent payment HRP: $hrp" }
        val data = result.second
        val version = data[0].toInt()
        require(version == 0) { "Unsupported silent payment version: $version" }
        val payload5Bit = ByteArray(data.size - 1) { i -> data[i + 1] }
        val program = convertBits(payload5Bit, 5, 8, false)
        require(program.size == 66) { "Invalid silent payment payload size: ${program.size} bytes (expected 66)" }
        val scanPubKey = program.copyOfRange(0, 33)
        val spendPubKey = program.copyOfRange(33, 66)
        return Pair(scanPubKey, spendPubKey)
    }

    private fun serializeOutpoint(txid: String, vout: Int): ByteArray {
        val txidBytes = hexToBytes(txid).reversedArray()
        val voutBytes = ByteArray(4)
        voutBytes[0] = (vout and 0xFF).toByte()
        voutBytes[1] = ((vout shr 8) and 0xFF).toByte()
        voutBytes[2] = ((vout shr 16) and 0xFF).toByte()
        voutBytes[3] = ((vout shr 24) and 0xFF).toByte()
        return txidBytes + voutBytes
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val tagHash = md.digest(tag.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(tagHash)
        md.update(tagHash)
        md.update(message)
        return md.digest()
    }

    private fun java.math.BigInteger.to32ByteArray(): ByteArray {
        val bytes = this.toByteArray()
        if (bytes.size == 32) return bytes
        if (bytes.size > 32) {
            return bytes.copyOfRange(bytes.size - 32, bytes.size)
        }
        val res = ByteArray(32)
        System.arraycopy(bytes, 0, res, 32 - bytes.size, bytes.size)
        return res
    }

    fun buildAndSignSilentPaymentTransaction(
        utxos: List<Utxo>,
        silentPaymentAddress: String,
        amountSats: Long,
        changeAddress: String,
        feeRateSatsPerVByte: Long,
        privateKey: PrivateKey,
        privateKeys: Map<Int, PrivateKey> = emptyMap()
    ): String {
        // 1. Decode silent payment keys
        val (scanPubKeyBytes, spendPubKeyBytes) = decodeSilentPaymentAddress(silentPaymentAddress)
        val B_scan = Secp256k1Math.parsePoint(ByteVector(scanPubKeyBytes).toHex())
        val B_spend = Secp256k1Math.parsePoint(ByteVector(spendPubKeyBytes).toHex())

        // 2. Compute sum of input public keys: A_sum = A_0 + A_1 + ...
        // and sum of private key values: privateKeyValSum = a_0 + a_1 + ...
        var sumInputPubkeysPoint = Secp256k1Math.parsePoint(
            (privateKeys[utxos[0].addressIndex] ?: privateKey).publicKey().value.toHex()
        )
        var privateKeyValSum = java.math.BigInteger(
            1, 
            (privateKeys[utxos[0].addressIndex] ?: privateKey).value.toByteArray()
        ).mod(Secp256k1Math.N)

        for (i in 1 until utxos.size) {
            val inputPrivKey = privateKeys[utxos[i].addressIndex] ?: privateKey
            val inputPubkey = inputPrivKey.publicKey()
            val inputPoint = Secp256k1Math.parsePoint(inputPubkey.value.toHex())
            sumInputPubkeysPoint = Secp256k1Math.add(sumInputPubkeysPoint, inputPoint)
            
            val privVal = java.math.BigInteger(1, inputPrivKey.value.toByteArray()).mod(Secp256k1Math.N)
            privateKeyValSum = privateKeyValSum.add(privVal).mod(Secp256k1Math.N)
        }
        val sumInputPubkeysSerialized = hexToBytes(sumInputPubkeysPoint.toHex())

        // 3. Sort outpoints lexicographically and get the lowest
        val sortedOutpoints = utxos.map { utxo ->
            serializeOutpoint(utxo.txid, utxo.vout)
        }.sortedWith { o1, o2 ->
            for (i in 0 until 36) {
                val b1 = o1[i].toInt() and 0xFF
                val b2 = o2[i].toInt() and 0xFF
                if (b1 != b2) return@sortedWith b1.compareTo(b2)
            }
            0
        }
        val lowestOutpointBytes = sortedOutpoints[0]

        // 4. Compute input hash
        val concatBytes = lowestOutpointBytes + sumInputPubkeysSerialized
        val inputHash = taggedHash("BIP0352/Inputs", concatBytes)
        val inputHashVal = java.math.BigInteger(1, inputHash).mod(Secp256k1Math.N)

        // 5. Compute ECDH shared secret: shared_secret = input_hash * a_sum * B_scan
        val scalar = inputHashVal.multiply(privateKeyValSum).mod(Secp256k1Math.N)
        val ecdhSharedSecretPoint = Secp256k1Math.multiply(B_scan, scalar)
        val ecdhSharedSecretBytes = hexToBytes(ecdhSharedSecretPoint.toHex())

        // 6. Compute tweak: t_0 = taggedHash("BIP0352/SharedSecret", ecdhSharedSecretBytes + index_0)
        val t0Bytes = taggedHash("BIP0352/SharedSecret", ecdhSharedSecretBytes + byteArrayOf(0, 0, 0, 0))
        val t0Val = java.math.BigInteger(1, t0Bytes).mod(Secp256k1Math.N)

        // 7. Compute tweaked recipient pubkey P_0 = B_spend + t_0 * G
        val t0G = Secp256k1Math.multiply(Secp256k1Math.G, t0Val)
        val p0Point = Secp256k1Math.add(B_spend, t0G)
        val p0XOnlyBytes = p0Point.x.to32ByteArray()

        // Recipient P2TR Output Script
        val recipientScript = byteArrayOf(0x51.toByte(), 0x20.toByte()) + p0XOnlyBytes

        // 8. Map UTXOs to TxIn
        val txIns = utxos.map { utxo ->
            val txId = TxId(utxo.txid)
            val outPoint = OutPoint(txId, utxo.vout.toLong())
            TxIn(outPoint, ByteVector.empty, sequence = 0xffffffffL)
        }

        val totalInputSats = utxos.sumOf { it.value }

        // 9. Compute fee with recipient output size = 34 bytes (P2TR)
        val estimatedVSize = 11 + (txIns.size * 68) + 43 + 31
        val feeSats = estimatedVSize * feeRateSatsPerVByte
        val changeSats = totalInputSats - amountSats - feeSats

        if (changeSats < 0) {
            throw IllegalArgumentException("Insufficient funds. Available: $totalInputSats, Spend: $amountSats, Estimated Fee: $feeSats")
        }

        val txOuts = mutableListOf<TxOut>()
        txOuts.add(TxOut(Satoshi(amountSats), recipientScript))

        if (changeSats >= 546L) {
            val changeScript = addressToScriptPubKey(changeAddress)
            txOuts.add(TxOut(Satoshi(changeSats), changeScript))
        }

        val unsignedTx = Transaction(version = 2L, txIn = txIns, txOut = txOuts, lockTime = 0L)

        // 10. Sign inputs (spending Native SegWit/P2WPKH UTXOs)
        val signedTxIns = txIns.mapIndexed { index, txIn ->
            val utxo = utxos[index]
            val inputPrivKey = privateKeys[utxo.addressIndex] ?: privateKey
            val inputPubkey = inputPrivKey.publicKey()
            val scriptCode = Script.pay2pkh(inputPubkey)
            val hash = unsignedTx.hashForSigning(
                inputIndex = index,
                previousOutputScript = scriptCode,
                sighashType = 1,
                amount = Satoshi(utxo.value),
                signatureVersion = 1
            )

            val sig = Crypto.sign(ByteVector32(hash), inputPrivKey)
            val derSig = derEncode(sig)
            val signedSig = derSig + byteArrayOf(1)

            val scriptWitness = ScriptWitness(listOf(
                ByteVector(signedSig),
                inputPubkey.value
            ))

            txIn.copy(witness = scriptWitness)
        }

        val signedTx = unsignedTx.copy(txIn = signedTxIns)
        return ByteVector(Transaction.write(signedTx)).toHex()
    }
}
