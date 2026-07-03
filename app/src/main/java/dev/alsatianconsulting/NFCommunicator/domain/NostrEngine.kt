package dev.alsatianconsulting.NFCommunicator.domain

import fr.acinq.bitcoin.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.ChaCha20ParameterSpec

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

    /**
     * Safely escapes strings for standard JSON output.
     */
    fun escapeJsonString(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Serializes event tags into a deterministic JSON array format without unnecessary spacing.
     */
    fun serializeTags(tags: List<List<String>>): String {
        return tags.joinToString(separator = ",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
                "\"${escapeJsonString(item)}\""
            }
        }
    }

    /**
     * Serializes a Nostr event for NIP-01 canonical signing.
     */
    fun serializeEventForSigning(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        val serializedTags = serializeTags(tags)
        return "[0,\"${pubkey.lowercase()}\",$createdAt,$kind,$serializedTags,\"${escapeJsonString(content)}\"]"
    }

    /**
     * Signs a Nostr event JSON. Returns a Pair(signatureHex, signedEventJson).
     */
    fun signEvent(eventJson: String, privateKeyHex: String): Pair<String, String> {
        val privKey = KeyParser.parsePrivateKey(privateKeyHex) ?: throw IllegalArgumentException("Invalid private key")
        val derivedPubkeyHex = ByteVector(privKey.publicKey().value.toByteArray().sliceArray(1..32)).toHex()

        val obj = org.json.JSONObject(eventJson)
        val createdAt = if (obj.has("created_at")) obj.getLong("created_at") else System.currentTimeMillis() / 1000L
        val kind = if (obj.has("kind")) obj.getInt("kind") else 1
        val content = if (obj.has("content")) obj.getString("content") else ""

        val tags = mutableListOf<List<String>>()
        val tagsArray = obj.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tagArray = tagsArray.optJSONArray(i)
                if (tagArray != null) {
                    val tagList = mutableListOf<String>()
                    for (j in 0 until tagArray.length()) {
                        tagList.add(tagArray.optString(j, ""))
                    }
                    tags.add(tagList)
                }
            }
        }

        val serialized = serializeEventForSigning(derivedPubkeyHex, createdAt, kind, tags, content)
        val hash = Crypto.sha256(serialized.toByteArray(Charsets.UTF_8))
        val hash32 = ByteVector32(hash)

        val signature = Crypto.signSchnorr(hash32, privKey, null, null)
        val sigHex = ByteVector(signature.toByteArray()).toHex()
        val idHex = hash32.toHex()

        val signedJson = """{"id":"$idHex","pubkey":"$derivedPubkeyHex","created_at":$createdAt,"kind":$kind,"tags":${serializeTags(tags)},"content":"${escapeJsonString(content)}","sig":"$sigHex"}"""
        return Pair(sigHex, signedJson)
    }

    /**
     * Performs NIP-04 AES-256-CBC encryption using the ECDH shared secret.
     */
    fun nip04Encrypt(plaintext: String, destPubkeyHex: String, privateKeyHex: String): String {
        val privKey = KeyParser.parsePrivateKey(privateKeyHex) ?: throw IllegalArgumentException("Invalid private key")
        val targetPubBytes = byteArrayOf(0x02.toByte()) + ByteVector(destPubkeyHex).toByteArray()
        val parsedPub = PublicKey(ByteVector(targetPubBytes))

        val sharedSecret = parsedPub.times(privKey).value.toByteArray().sliceArray(1..32)

        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        val random = java.security.SecureRandom()
        val iv = ByteArray(16)
        random.nextBytes(iv)
        val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
        val keySpec = javax.crypto.spec.SecretKeySpec(sharedSecret, "AES")

        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val encBase64 = java.util.Base64.getEncoder().encodeToString(encrypted)
        val ivBase64 = java.util.Base64.getEncoder().encodeToString(iv)

        return "$encBase64?iv=$ivBase64"
    }

    /**
     * Performs NIP-04 AES-256-CBC decryption using the ECDH shared secret.
     */
    fun nip04Decrypt(ciphertextWithIv: String, destPubkeyHex: String, privateKeyHex: String): String {
        val privKey = KeyParser.parsePrivateKey(privateKeyHex) ?: throw IllegalArgumentException("Invalid private key")
        val targetPubBytes = byteArrayOf(0x02.toByte()) + ByteVector(destPubkeyHex).toByteArray()
        val parsedPub = PublicKey(ByteVector(targetPubBytes))

        val sharedSecret = parsedPub.times(privKey).value.toByteArray().sliceArray(1..32)

        val parts = ciphertextWithIv.split("?iv=")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid NIP-04 ciphertext format")
        }

        val encryptedBytes = java.util.Base64.getDecoder().decode(parts[0])
        val ivBytes = java.util.Base64.getDecoder().decode(parts[1])

        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)
        val keySpec = javax.crypto.spec.SecretKeySpec(sharedSecret, "AES")

        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(encryptedBytes)

        return String(decrypted, Charsets.UTF_8)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    private fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        val nextPower = Integer.highestOneBit(unpaddedLen - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    private fun pad(plaintext: ByteArray): ByteArray {
        val unpaddedLen = plaintext.size
        if (unpaddedLen < 1 || unpaddedLen > 65535) {
            throw IllegalArgumentException("Plaintext length must be between 1 and 65535 bytes")
        }
        val paddedLen = calcPaddedLen(unpaddedLen)
        val padded = ByteArray(paddedLen)
        padded[0] = (unpaddedLen shr 8).toByte()
        padded[1] = (unpaddedLen and 0xFF).toByte()
        System.arraycopy(plaintext, 0, padded, 2, unpaddedLen)
        return padded
    }

    private fun unpad(padded: ByteArray): ByteArray {
        val unpaddedLen = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (unpaddedLen < 1 || unpaddedLen > padded.size - 2) {
            throw IllegalArgumentException("Invalid padded message length")
        }
        val plaintext = ByteArray(unpaddedLen)
        System.arraycopy(padded, 2, plaintext, 0, unpaddedLen)
        return plaintext
    }

    fun nip44Encrypt(plaintext: String, destPubkeyHex: String, privateKeyHex: String): String {
        val privKey = KeyParser.parsePrivateKey(privateKeyHex) ?: throw IllegalArgumentException("Invalid private key")
        val targetPubBytes = byteArrayOf(0x02.toByte()) + ByteVector(destPubkeyHex).toByteArray()
        val parsedPub = PublicKey(ByteVector(targetPubBytes))

        // ECDH Shared Secret
        val sharedSecret = parsedPub.times(privKey).value.toByteArray().sliceArray(1..32)

        // Derive Conversation Key
        val salt = "nip44-v2".toByteArray(Charsets.UTF_8)
        val conversationKey = hkdfExtract(salt, sharedSecret)

        // Generate random 32-byte nonce
        val nonce = ByteArray(32)
        SecureRandom().nextBytes(nonce)

        // Derive Message Keys
        val messageKeys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = messageKeys.sliceArray(0..31)
        val chachaNonce = messageKeys.sliceArray(32..43)
        val hmacKey = messageKeys.sliceArray(44..75)

        // Pad Plaintext
        val paddedPlaintext = pad(plaintext.toByteArray(Charsets.UTF_8))

        // Encrypt with ChaCha20 (RFC 7539 uses counter=0)
        val cipher = Cipher.getInstance("ChaCha20")
        val keySpec = SecretKeySpec(chachaKey, "ChaCha20")
        val paramSpec = javax.crypto.spec.ChaCha20ParameterSpec(chachaNonce, 0)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec)
        val ciphertext = cipher.doFinal(paddedPlaintext)

        // Calculate HMAC-SHA256 over nonce + ciphertext
        val macInput = nonce + ciphertext
        val macBytes = hmacSha256(hmacKey, macInput)

        // Construct final payload: Version (0x02) + Nonce (32) + Ciphertext + MAC (32)
        val finalPayload = ByteArray(1 + nonce.size + ciphertext.size + macBytes.size)
        finalPayload[0] = 0x02.toByte()
        System.arraycopy(nonce, 0, finalPayload, 1, nonce.size)
        System.arraycopy(ciphertext, 0, finalPayload, 1 + nonce.size, ciphertext.size)
        System.arraycopy(macBytes, 0, finalPayload, 1 + nonce.size + ciphertext.size, macBytes.size)

        return Base64.getEncoder().encodeToString(finalPayload)
    }

    fun nip44Decrypt(payloadBase64: String, destPubkeyHex: String, privateKeyHex: String): String {
        val privKey = KeyParser.parsePrivateKey(privateKeyHex) ?: throw IllegalArgumentException("Invalid private key")
        val targetPubBytes = byteArrayOf(0x02.toByte()) + ByteVector(destPubkeyHex).toByteArray()
        val parsedPub = PublicKey(ByteVector(targetPubBytes))

        // ECDH Shared Secret
        val sharedSecret = parsedPub.times(privKey).value.toByteArray().sliceArray(1..32)

        // Derive Conversation Key
        val salt = "nip44-v2".toByteArray(Charsets.UTF_8)
        val conversationKey = hkdfExtract(salt, sharedSecret)

        // Decode Payload
        val payloadBytes = Base64.getDecoder().decode(payloadBase64.trim())
        if (payloadBytes.isEmpty() || payloadBytes[0].toInt() != 0x02) {
            throw IllegalArgumentException("Unsupported NIP-44 version: ${payloadBytes.getOrNull(0)?.toInt()}")
        }

        if (payloadBytes.size < 1 + 32 + 32) {
            throw IllegalArgumentException("Invalid NIP-44 payload size")
        }

        val nonce = payloadBytes.sliceArray(1..32)
        val ciphertext = payloadBytes.sliceArray(33 until (payloadBytes.size - 32))
        val mac = payloadBytes.sliceArray((payloadBytes.size - 32) until payloadBytes.size)

        // Derive Message Keys
        val messageKeys = hkdfExpand(conversationKey, nonce, 76)
        val chachaKey = messageKeys.sliceArray(0..31)
        val chachaNonce = messageKeys.sliceArray(32..43)
        val hmacKey = messageKeys.sliceArray(44..75)

        // Verify MAC
        val macInput = nonce + ciphertext
        val calculatedMac = hmacSha256(hmacKey, macInput)
        if (!MessageDigest.isEqual(calculatedMac, mac)) {
            throw SecurityException("NIP-44 MAC verification failed")
        }

        // Decrypt with ChaCha20
        val cipher = Cipher.getInstance("ChaCha20")
        val keySpec = SecretKeySpec(chachaKey, "ChaCha20")
        val paramSpec = javax.crypto.spec.ChaCha20ParameterSpec(chachaNonce, 0)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
        val paddedPlaintext = cipher.doFinal(ciphertext)

        // Unpad Plaintext
        val plaintextBytes = unpad(paddedPlaintext)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

