package dev.alsatianconsulting.NFCommunicator.domain

import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey

object KeyParser {

    /**
     * Tries to parse a private key from various string formats: WIF, Hex, or Extended Private Key.
     * Returns a PrivateKey object if successful, or null otherwise.
     */
    fun parsePrivateKey(input: String): PrivateKey? {
        val clean = input.trim()
        if (clean.isEmpty()) return null

        // 1. Try parsing as WIF (Wallet Import Format)
        try {
            val decoded = Base58Check.decode(clean)
            val prefix = decoded.first.toInt() and 0xff
            // Mainnet private key prefix is 128 (0x80), Testnet is 239 (0xef)
            if (prefix == 0x80 || prefix == 0xef) {
                val payload = decoded.second
                // A WIF payload is either 32 bytes (uncompressed) or 33 bytes (compressed, last byte is 0x01)
                if (payload.size == 32) {
                    return PrivateKey(payload)
                } else if (payload.size == 33 && payload[32] == 1.toByte()) {
                    return PrivateKey(payload.sliceArray(0 until 32))
                }
            }
        } catch (e: Exception) {
            // Not a valid Base58Check or WIF
        }

        // 2. Try parsing as Extended Private Key (BIP32 xprv / zprv etc.)
        if (clean.startsWith("xprv") || clean.startsWith("yprv") || clean.startsWith("zprv") ||
            clean.startsWith("tprv") || clean.startsWith("uprv") || clean.startsWith("vprv")) {
            try {
                val decoded = DeterministicWallet.ExtendedPrivateKey.decode(clean, KeyPath(""))
                val extPrivKey = decoded.second
                return try {
                    // Try master path derivation
                    val derived = extPrivKey.derivePrivateKey("m/84'/0'/0'/0/0")
                    derived.privateKey
                } catch (e: Exception) {
                    try {
                        // Fallback to account path derivation
                        val derived = extPrivKey.derivePrivateKey("m/0/0")
                        derived.privateKey
                    } catch (e2: Exception) {
                        // Fallback to using the extended key's private key directly
                        extPrivKey.privateKey
                    }
                }
            } catch (e: Exception) {
                // Not a valid serialized BIP32 extended private key
            }
        }

        // 3. Try parsing as raw Hex private key
        if (clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            try {
                val bytes = hexToBytes(clean)
                if (bytes.size == 32) {
                    return PrivateKey(bytes)
                }
            } catch (e: Exception) {
                // Not a valid hex
            }
        }

        return null
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
