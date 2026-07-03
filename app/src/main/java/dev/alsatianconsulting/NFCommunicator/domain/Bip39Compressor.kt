/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
package dev.alsatianconsulting.NFCommunicator.domain

import fr.acinq.bitcoin.MnemonicCode

object Bip39Compressor {
    private val wordList: List<String> by lazy {
        MnemonicCode.englishWordlist
    }

    private val wordIndexMap: Map<String, Int> by lazy {
        wordList.withIndex().associate { it.value to it.index }
    }

    /**
     * Parses and normalizes a mnemonic string into a list of words.
     * It splits by whitespace, converts to lowercase, filters out non-alphabetic
     * characters (removing numbers, punctuation, zero-width spaces, etc.),
     * and filters out empty strings.
     */
    fun cleanAndSplitMnemonic(mnemonicString: String): List<String> {
        return mnemonicString.trim()
            .split(Regex("\\s+"))
            .map { word -> word.lowercase().filter { it in 'a'..'z' } }
            .filter { it.isNotEmpty() }
    }

    /**
     * Compresses a list of BIP-39 mnemonic words into raw binary entropy.
     * Returns 16 bytes for 12 words, or 32 bytes for 24 words.
     */
    fun mnemonicToEntropy(words: List<String>): ByteArray {
        require(words.size == 12 || words.size == 24) {
            "Mnemonic must be exactly 12 or 24 words"
        }

        // Validate the mnemonic phrase using the bitcoin-kmp library
        try {
            MnemonicCode.validate(words, wordList)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid BIP-39 mnemonic: ${e.message}", e)
        }

        val entropySizeBits = if (words.size == 12) 128 else 256
        val entropySizeBytes = entropySizeBits / 8

        // Convert each word to its 11-bit index
        val indices = words.map { word ->
            wordIndexMap[word] ?: throw IllegalArgumentException("Word '$word' not in BIP-39 wordlist")
        }

        // Convert indices to a big bit string
        val bitString = StringBuilder()
        for (index in indices) {
            val binaryString = Integer.toBinaryString(index).padStart(11, '0')
            bitString.append(binaryString)
        }

        // Extract raw entropy bytes
        val entropy = ByteArray(entropySizeBytes)
        for (i in 0 until entropySizeBytes) {
            val byteStr = bitString.substring(i * 8, (i + 1) * 8)
            entropy[i] = Integer.parseInt(byteStr, 2).toByte()
        }

        return entropy
    }

    /**
     * Decompresses raw binary entropy (16 or 32 bytes) back into the 12/24-word BIP-39 mnemonic list.
     */
    fun entropyToMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size == 16 || entropy.size == 32) {
            "Entropy must be exactly 16 or 32 bytes"
        }
        return MnemonicCode.toMnemonics(entropy, wordList)
    }

    /**
     * Generates a random 12 or 24-word BIP-39 mnemonic phrase.
     */
    fun generateMnemonic(wordCount: Int): List<String> {
        require(wordCount == 12 || wordCount == 24) {
            "Word count must be 12 or 24"
        }
        val entropySize = if (wordCount == 12) 16 else 32
        val entropy = ByteArray(entropySize)
        java.security.SecureRandom().nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }
}
