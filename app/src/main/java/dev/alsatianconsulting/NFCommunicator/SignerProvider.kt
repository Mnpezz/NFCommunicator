package dev.alsatianconsulting.NFCommunicator

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class SignerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        android.util.Log.d("NfcSignerProvider", "query: uri=$uri, projection=${projection?.joinToString(",")}, selection=$selection, selectionArgs=${selectionArgs?.joinToString(",")}")
        val host = uri.host ?: return null
        val requestType = when {
            host.endsWith("GET_PUBLIC_KEY") -> "get_public_key"
            host.endsWith("SIGN_EVENT") -> "sign_event"
            host.endsWith("NIP04_ENCRYPT") -> "nip04_encrypt"
            host.endsWith("NIP04_DECRYPT") -> "nip04_decrypt"
            host.endsWith("NIP44_ENCRYPT") -> "nip44_encrypt"
            host.endsWith("NIP44_DECRYPT") -> "nip44_decrypt"
            host.endsWith("DECRYPT_ZAP_EVENT") -> "decrypt_zap_event"
            else -> return null
        }

        val prefs = context.getSharedPreferences("auto_sign_prefs", android.content.Context.MODE_PRIVATE)

        // Security check: Only allow approved packages to query the content provider.
        val caller = callingPackage
        val myPackage = context.packageName
        if (caller != null && caller != myPackage) {
            val approvedSet = prefs.getStringSet("approved_packages", emptySet()) ?: emptySet()
            if (!approvedSet.contains(caller)) {
                android.util.Log.w("NfcSignerProvider", "Query rejected: caller package '$caller' is not in approved allowlist")
                return null
            }
        } else if (caller == null) {
            android.util.Log.w("NfcSignerProvider", "Query rejected: calling package is null")
            return null
        }

        try {
            val payload = selectionArgs?.getOrNull(0) ?: projection?.getOrNull(0)
            val rawDestPubkey = selectionArgs?.getOrNull(1) ?: projection?.getOrNull(1)
            val destPubkey = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.normalizeNostrPubKey(rawDestPubkey)

            val argsSize = selectionArgs?.size ?: projection?.size ?: 0

            val explicitIv = if (requestType == "nip04_decrypt" || requestType == "nip04_encrypt") {
                if (argsSize >= 4) {
                    selectionArgs?.getOrNull(2) ?: projection?.getOrNull(2)
                } else null
            } else null

            val rawCurrentUser = if (requestType == "nip04_decrypt" || requestType == "nip04_encrypt") {
                if (argsSize >= 4) {
                    selectionArgs?.getOrNull(3) ?: projection?.getOrNull(3)
                } else {
                    selectionArgs?.getOrNull(2) ?: projection?.getOrNull(2)
                }
            } else {
                selectionArgs?.getOrNull(2) ?: projection?.getOrNull(2)
            }
            val providerCurrentUser = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.normalizeNostrPubKey(rawCurrentUser)

            val kind = if (requestType == "sign_event" && payload != null) {
                runCatching { org.json.JSONObject(payload).getInt("kind") }.getOrNull()
            } else null

            val isAutoApproved = when (requestType) {
                "get_public_key" -> true
                "sign_event" -> {
                    if (kind == null) false else {
                        when (kind) {
                            22242 -> prefs.getBoolean("kind_22242", false)
                            10050 -> prefs.getBoolean("kind_10050", false)
                            31234 -> prefs.getBoolean("kind_31234", false)
                            5 -> prefs.getBoolean("kind_5", false)
                            else -> false
                        }
                    }
                }
                "nip04_encrypt", "nip44_encrypt" -> prefs.getBoolean("nip_encrypt", false)
                "nip04_decrypt", "nip44_decrypt" -> prefs.getBoolean("nip_decrypt", false)
                else -> false
            }

            android.util.Log.d("NfcSignerProvider", "query: requestType=$requestType, kind=$kind, isAutoApproved=$isAutoApproved")
            if (!isAutoApproved) {
                return null
            }

            val mnemonic = NfcViewModel.getInMemoryReadMessage() ?: return null
            val keys = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.deriveNostrKeys(mnemonic) ?: return null
            val privateKeyHex = keys.privkeyHex

            // Identity safety check: Validate that the requested current_user matches our active pubkey
            val activePubkey = keys.pubkeyHex.trim().lowercase()
            if (!providerCurrentUser.isNullOrEmpty() && activePubkey != providerCurrentUser) {
                android.util.Log.w("NfcSignerProvider", "Query rejected: active pubkey ($activePubkey) does not match expected pubkey ($providerCurrentUser)")
                val errorCursor = MatrixCursor(arrayOf("signature", "result", "event", "rejected"))
                errorCursor.addRow(arrayOf("", "", "", "true"))
                return errorCursor
            }

            var resultString = ""
            var signedEventJson: String? = null

            when (requestType) {
                "get_public_key" -> {
                    resultString = keys.pubkeyHex
                }
                "sign_event" -> {
                    val eventJson = payload ?: return null
                    val (sigHex, signedJson) = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.signEvent(eventJson, privateKeyHex)
                    resultString = sigHex
                    signedEventJson = signedJson
                }
                "nip04_encrypt" -> {
                    val plaintext = payload ?: return null
                    val peerPubkey = destPubkey ?: return null
                    resultString = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.nip04Encrypt(plaintext, peerPubkey, privateKeyHex)
                }
                "nip04_decrypt" -> {
                    val ciphertext = payload ?: return null
                    val peerPubkey = destPubkey ?: return null
                    resultString = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.nip04Decrypt(ciphertext, peerPubkey, privateKeyHex, explicitIv)
                }
                "nip44_encrypt" -> {
                    val plaintext = payload ?: return null
                    val peerPubkey = destPubkey ?: return null
                    resultString = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.nip44Encrypt(plaintext, peerPubkey, privateKeyHex)
                }
                "nip44_decrypt" -> {
                    val ciphertext = payload ?: return null
                    val peerPubkey = destPubkey ?: return null
                    resultString = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.nip44Decrypt(ciphertext, peerPubkey, privateKeyHex)
                }
                "decrypt_zap_event" -> {
                    val eventStr = payload ?: return null
                    val eventObj = org.json.JSONObject(eventStr)
                    val tags = eventObj.optJSONArray("tags")
                    var description: String? = null
                    if (tags != null) {
                        for (i in 0 until tags.length()) {
                            val tag = tags.optJSONArray(i)
                            if (tag != null && tag.optString(0) == "description") {
                                description = tag.optString(1)
                                break
                            }
                        }
                    }
                    resultString = if (description != null) {
                        val zapRequest = runCatching { org.json.JSONObject(description) }.getOrNull()
                        val zapContent = zapRequest?.optString("content", "") ?: ""
                        val zapperPubkey = zapRequest?.optString("pubkey", "") ?: ""
                        if (zapContent.isNotEmpty() && zapperPubkey.isNotEmpty()) {
                            dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.nip04Decrypt(zapContent, zapperPubkey, privateKeyHex)
                        } else {
                            description
                        }
                    } else {
                        val content = eventObj.optString("content", "")
                        val pubkey = eventObj.optString("pubkey", "")
                        if (content.isNotEmpty() && pubkey.isNotEmpty()) {
                            dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.nip04Decrypt(content, pubkey, privateKeyHex)
                        } else {
                            eventStr
                        }
                    }
                }
                else -> return null
            }

            val columns = if (signedEventJson != null) {
                arrayOf("signature", "result", "event", "rejected")
            } else {
                arrayOf("signature", "result", "rejected")
            }

            val cursor = MatrixCursor(columns)
            val row = if (signedEventJson != null) {
                arrayOf(resultString, resultString, signedEventJson, "")
            } else {
                arrayOf(resultString, resultString, "")
            }
            cursor.addRow(row)
            return cursor
        } catch (e: Exception) {
            android.util.Log.e("NfcSignerProvider", "Query failed with exception", e)
            val errorCursor = MatrixCursor(arrayOf("signature", "result", "event", "rejected"))
            errorCursor.addRow(arrayOf("", "", "", "true"))
            return errorCursor
        }
    }


    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
