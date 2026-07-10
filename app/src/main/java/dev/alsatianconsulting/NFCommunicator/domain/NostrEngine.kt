package dev.alsatianconsulting.NFCommunicator.domain

import fr.acinq.bitcoin.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

        // 1. nsec bech32 → reconstruct key pair directly
        val nsecPrivKey = KeyParser.parseNsec(clean)
        if (nsecPrivKey != null) {
            return try {
                val privBytes = nsecPrivKey.value.toByteArray()
                val pubkey = nsecPrivKey.publicKey()
                val pubBytes = pubkey.value.toByteArray()
                val xOnlyPubBytes = pubBytes.sliceArray(1..32)

                NostrKeyPair(
                    nsec = clean, // keep the original nsec the user stored
                    npub = Bech32.encodeBytes("npub", xOnlyPubBytes, Bech32.Encoding.Bech32),
                    pubkeyHex = ByteVector(xOnlyPubBytes).toHex(),
                    privkeyHex = ByteVector(privBytes).toHex()
                )
            } catch (e: Exception) {
                null
            }
        }

        // 2. Other raw private key (WIF/Hex/xprv) → derive nsec/npub
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

    fun normalizeNostrPubKey(pubkey: String?): String? {
        val clean = pubkey?.trim() ?: return null
        if (clean.isEmpty()) return null
        if (clean.startsWith("npub1", ignoreCase = true)) {
            return try {
                val (hrp, bytes, _) = fr.acinq.bitcoin.Bech32.decodeBytes(clean)
                if (hrp == "npub") {
                    fr.acinq.bitcoin.ByteVector(bytes).toHex().lowercase()
                } else {
                    clean.lowercase()
                }
            } catch (e: Exception) {
                clean.lowercase()
            }
        }
        return clean.lowercase()
    }

    fun decodeIvString(ivStr: String): ByteArray {
        val trimmed = ivStr.trim()
        if (trimmed.isEmpty()) {
            return ByteArray(0)
        }
        // Try hex decoding first if it matches a 32-char hex pattern or only contains hex characters
        if (trimmed.length == 32 || trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            val hexBytes = runCatching { fr.acinq.bitcoin.ByteVector(trimmed).toByteArray() }.getOrNull()
            if (hexBytes != null && hexBytes.size == 16) {
                return hexBytes
            }
        }
        // Try standard base64 decoding
        val b64Bytes = runCatching { java.util.Base64.getDecoder().decode(trimmed) }.getOrNull()
        if (b64Bytes != null && b64Bytes.size == 16) {
            return b64Bytes
        }
        // Try URL-safe base64 decoding
        val urlSafeFixed = trimmed.replace('-', '+').replace('_', '/')
        val padded = urlSafeFixed + "=".repeat((4 - urlSafeFixed.length % 4) % 4)
        val urlSafeBytes = runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
        if (urlSafeBytes != null && urlSafeBytes.size == 16) {
            return urlSafeBytes
        }
        // Fallback: return whichever decoded successfully
        return b64Bytes ?: urlSafeBytes ?: ByteArray(0)
    }

    fun decodeBase64String(b64Str: String): ByteArray {
        val trimmed = b64Str.trim()
        val standard = runCatching { java.util.Base64.getDecoder().decode(trimmed) }.getOrNull()
        if (standard != null) return standard
        val urlSafeFixed = trimmed.replace('-', '+').replace('_', '/')
        val padded = urlSafeFixed + "=".repeat((4 - urlSafeFixed.length % 4) % 4)
        return runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
            ?: throw IllegalArgumentException("Failed to base64-decode data")
    }

    /**
     * Performs NIP-04 AES-256-CBC decryption using the ECDH shared secret.
     */
    fun nip04Decrypt(ciphertextWithIv: String, destPubkeyHex: String, privateKeyHex: String, explicitIvBase64: String? = null): String {
        val privKey = KeyParser.parsePrivateKey(privateKeyHex) ?: throw IllegalArgumentException("Invalid private key")
        val targetPubBytes = byteArrayOf(0x02.toByte()) + ByteVector(destPubkeyHex).toByteArray()
        val parsedPub = PublicKey(ByteVector(targetPubBytes))

        val sharedSecret = parsedPub.times(privKey).value.toByteArray().sliceArray(1..32)

        val parts = ciphertextWithIv.split("?iv=")
        val encryptedBytes: ByteArray
        val ivBytes: ByteArray

        if (parts.size == 2) {
            encryptedBytes = decodeBase64String(parts[0])
            ivBytes = decodeIvString(parts[1])
        } else {
            val ivStr = explicitIvBase64 ?: throw IllegalArgumentException("Invalid NIP-04 ciphertext format: missing IV")
            encryptedBytes = decodeBase64String(ciphertextWithIv)
            ivBytes = decodeIvString(ivStr)
        }

        if (ivBytes.size != 16) {
            throw IllegalArgumentException("IV must be exactly 16 bytes")
        }

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

        // Encrypt with ChaCha20 (counter starts at 0)
        val cipher = Cipher.getInstance("ChaCha20")
        val keySpec = SecretKeySpec(chachaKey, "ChaCha20")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, getChaCha20ParameterSpec(chachaNonce))
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

        // Decode Payload — try standard base64 first, then URL-safe base64.
        // Some Nostr clients (e.g. Wisp) encode with base64url (RFC 4648 §5)
        // instead of standard base64, which causes a corrupted version byte
        // when decoded with the standard decoder.
        val payloadBytes: ByteArray = run {
            val trimmed = payloadBase64.trim()
            // Try standard base64 first (the NIP-44 v2 spec uses this)
            val standard = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()
            if (standard != null && standard.isNotEmpty() && (standard[0].toInt() and 0xFF) == 2) {
                return@run standard
            }
            // Fall back to URL-safe base64 (replaces - → + and _ → / then decodes)
            val urlSafeFixed = trimmed.replace('-', '+').replace('_', '/')
            val padded = urlSafeFixed + "=".repeat((4 - urlSafeFixed.length % 4) % 4)
            val urlSafe = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            if (urlSafe != null && urlSafe.isNotEmpty() && (urlSafe[0].toInt() and 0xFF) == 2) {
                return@run urlSafe
            }
            // Last resort: return whichever decoded successfully, even if version is wrong
            standard ?: urlSafe ?: throw IllegalArgumentException("Failed to base64-decode NIP-44 payload")
        }

        val version = payloadBytes.getOrNull(0)?.toInt()?.and(0xFF)
        if (version != 2) {
            throw IllegalArgumentException("Unsupported NIP-44 version: $version (expected 2)")
        }

        if (payloadBytes.size < 1 + 32 + 32) {
            throw IllegalArgumentException("Invalid NIP-44 payload size: ${payloadBytes.size} bytes")
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
        cipher.init(Cipher.DECRYPT_MODE, keySpec, getChaCha20ParameterSpec(chachaNonce))
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

    private fun getChaCha20ParameterSpec(nonce: ByteArray): java.security.spec.AlgorithmParameterSpec {
        return try {
            val clazz = Class.forName("javax.crypto.spec.ChaCha20ParameterSpec")
            val constructor = clazz.getConstructor(ByteArray::class.java, Int::class.javaPrimitiveType)
            constructor.newInstance(nonce, 0) as java.security.spec.AlgorithmParameterSpec
        } catch (e: Exception) {
            // Fallback for older Android APIs (< 33) where ChaCha20ParameterSpec doesn't exist
            javax.crypto.spec.IvParameterSpec(nonce)
        }
    }
}

