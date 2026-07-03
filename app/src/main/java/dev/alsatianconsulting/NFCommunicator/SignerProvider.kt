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

        val payload = selectionArgs?.getOrNull(0) ?: projection?.getOrNull(0)
        val destPubkey = selectionArgs?.getOrNull(1) ?: projection?.getOrNull(1)

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

        try {
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
                    resultString = dev.alsatianconsulting.NFCommunicator.domain.NostrEngine.nip04Decrypt(ciphertext, peerPubkey, privateKeyHex)
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
                else -> return null
            }

            val columns = if (signedEventJson != null) {
                arrayOf("signature", "result", "event")
            } else {
                arrayOf("signature", "result")
            }

            val cursor = MatrixCursor(columns)
            val row = if (signedEventJson != null) {
                arrayOf(resultString, resultString, signedEventJson)
            } else {
                arrayOf(resultString, resultString)
            }
            cursor.addRow(row)
            return cursor
        } catch (e: Exception) {
            return null
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
