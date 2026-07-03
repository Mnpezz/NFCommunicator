package dev.alsatianconsulting.NFCommunicator.domain

import fr.acinq.bitcoin.*

data class NostrKeyPair(
    val nsec: String,
    val npub: String,
    val pubkeyHex: String,
    val privkeyHex: String
)

object NostrEngine {
    const val NOSTR_DERIVATION_PATH = "m/44'/1237'/0'/0/0"

    /**
     * Derives Nostr keys (nsec/npub) from a BIP-39 mnemonic string or parses a raw private key.
     */
    fun deriveNostrKeys(input: String): NostrKeyPair? {
        val clean = input.trim()
        if (clean.isEmpty()) return null

        // 1. Try parsing as a raw private key first
        val parsedPrivKey = KeyParser.parsePrivateKey(clean)
        if (parsedPrivKey != null) {
            return try {
                val privBytes = parsedPrivKey.value.toByteArray()
                val pubkey = parsedPrivKey.publicKey()
                val pubBytes = pubkey.value.toByteArray()

                // Nostr x-only public key is the X-coordinate (last 32 bytes of 33-byte compressed pubkey)
                val xOnlyPubBytes = pubBytes.sliceArray(1..32)

                val nsec = Bech32.encodeBytes("nsec", privBytes, Bech32.Encoding.Bech32)
                val npub = Bech32.encodeBytes("npub", xOnlyPubBytes, Bech32.Encoding.Bech32)

                NostrKeyPair(
                    nsec = nsec,
                    npub = npub,
                    pubkeyHex = ByteVector(xOnlyPubBytes).toHex(),
                    privkeyHex = ByteVector(privBytes).toHex()
                )
            } catch (e: Exception) {
                null
            }
        }

        // 2. Fall back to BIP-39 mnemonic derivation
        val words = clean.lowercase().split(Regex("\\s+"))
        if (words.size != 12 && words.size != 24) return null

        return try {
            val seed = MnemonicCode.toSeed(words, "")
            val master = DeterministicWallet.generate(seed)
            val derived = DeterministicWallet.derivePrivateKey(master, NOSTR_DERIVATION_PATH)
            val privateKey = derived.privateKey

            val privBytes = privateKey.value.toByteArray()
            val pubkey = privateKey.publicKey()
            val pubBytes = pubkey.value.toByteArray()

            // Nostr x-only public key is the X-coordinate (last 32 bytes of 33-byte compressed pubkey)
            val xOnlyPubBytes = pubBytes.sliceArray(1..32)

            val nsec = Bech32.encodeBytes("nsec", privBytes, Bech32.Encoding.Bech32)
            val npub = Bech32.encodeBytes("npub", xOnlyPubBytes, Bech32.Encoding.Bech32)

            NostrKeyPair(
                nsec = nsec,
                npub = npub,
                pubkeyHex = ByteVector(xOnlyPubBytes).toHex(),
                privkeyHex = ByteVector(privBytes).toHex()
            )
        } catch (e: Exception) {
            null
        }
    }
}

