package dev.alsatianconsulting.NFCommunicator.data

import dev.alsatianconsulting.NFCommunicator.domain.BlindedMessage
import dev.alsatianconsulting.NFCommunicator.domain.BlindedSignature
import dev.alsatianconsulting.NFCommunicator.domain.CashuProof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class MintKeyset(
    val id: String,
    val unit: String,
    val keys: Map<Long, String>
)

data class MintQuoteResponse(
    val quote: String,
    val request: String,
    val state: String,
    val expiry: Long
)

data class MeltQuoteResponse(
    val quote: String,
    val amount: Long,
    val feeReserve: Long,
    val state: String
)

data class MeltResponse(
    val paid: Boolean,
    val preimage: String?,
    val change: List<BlindedSignature> = emptyList()
)

object CashuClient {

    private fun cleanUrl(url: String): String {
        return if (url.endsWith("/")) url.substring(0, url.length - 1) else url
    }

    suspend fun fetchKeysets(mintUrl: String): List<MintKeyset> = withContext(Dispatchers.IO) {
        val baseUrl = cleanUrl(mintUrl)
        val url = URL("$baseUrl/v1/keys")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                val keysetsArray = json.getJSONArray("keysets")
                val keysets = mutableListOf<MintKeyset>()
                
                for (i in 0 until keysetsArray.length()) {
                    val obj = keysetsArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val unit = obj.optString("unit", "sat")
                    val keysObj = obj.getJSONObject("keys")
                    val keysMap = mutableMapOf<Long, String>()
                    
                    keysObj.keys().forEach { keyStr ->
                        val amount = keyStr.toLongOrNull()
                        if (amount != null) {
                            keysMap[amount] = keysObj.getString(keyStr)
                        }
                    }
                    keysets.add(MintKeyset(id, unit, keysMap))
                }
                keysets
            } else {
                throw Exception("Failed to fetch mint keysets: HTTP $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun requestMintQuote(mintUrl: String, amount: Long): MintQuoteResponse = withContext(Dispatchers.IO) {
        val baseUrl = cleanUrl(mintUrl)
        val url = URL("$baseUrl/v1/mint/quote/bolt11")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Content-Type", "application/json")

        val payload = JSONObject().apply {
            put("amount", amount)
            put("unit", "sat")
        }

        try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                MintQuoteResponse(
                    quote = json.getString("quote"),
                    request = json.getString("request"),
                    state = json.optString("state", "UNPAID"),
                    expiry = json.optLong("expiry", 0L)
                )
            } else {
                val err = readErrorStream(connection)
                throw Exception("Failed to request mint quote: HTTP $responseCode - $err")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun checkMintQuote(mintUrl: String, quoteId: String): MintQuoteResponse = withContext(Dispatchers.IO) {
        val baseUrl = cleanUrl(mintUrl)
        val url = URL("$baseUrl/v1/mint/quote/bolt11/$quoteId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                MintQuoteResponse(
                    quote = json.getString("quote"),
                    request = json.getString("request"),
                    state = json.optString("state", "UNPAID"),
                    expiry = json.optLong("expiry", 0L)
                )
            } else {
                throw Exception("Failed to check mint quote: HTTP $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun mintTokens(
        mintUrl: String,
        quoteId: String,
        outputs: List<BlindedMessage>
    ): List<BlindedSignature> = withContext(Dispatchers.IO) {
        val baseUrl = cleanUrl(mintUrl)
        val url = URL("$baseUrl/v1/mint/bolt11")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Content-Type", "application/json")

        val outputsArray = JSONArray()
        outputs.forEach { out ->
            outputsArray.put(JSONObject().apply {
                put("amount", out.amount)
                put("id", out.id)
                put("B_", out.B_)
            })
        }

        val payload = JSONObject().apply {
            put("quote", quoteId)
            put("outputs", outputsArray)
        }

        try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                val sigsArray = json.getJSONArray("signatures")
                val signatures = mutableListOf<BlindedSignature>()
                
                for (i in 0 until sigsArray.length()) {
                    val obj = sigsArray.getJSONObject(i)
                    signatures.add(
                        BlindedSignature(
                            amount = obj.getLong("amount"),
                            id = obj.getString("id"),
                            C_ = obj.getString("C_")
                        )
                    )
                }
                signatures
            } else {
                val err = readErrorStream(connection)
                throw Exception("Failed to mint eCash: HTTP $responseCode - $err")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun requestMeltQuote(mintUrl: String, invoice: String): MeltQuoteResponse = withContext(Dispatchers.IO) {
        val baseUrl = cleanUrl(mintUrl)
        val url = URL("$baseUrl/v1/melt/quote/bolt11")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Content-Type", "application/json")

        val payload = JSONObject().apply {
            put("request", invoice)
            put("unit", "sat")
        }

        try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                MeltQuoteResponse(
                    quote = json.getString("quote"),
                    amount = json.getLong("amount"),
                    feeReserve = json.optLong("fee_reserve", 0L),
                    state = json.optString("state", "UNPAID")
                )
            } else {
                val err = readErrorStream(connection)
                throw Exception("Failed to request melt quote: HTTP $responseCode - $err")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun meltTokens(
        mintUrl: String,
        quoteId: String,
        inputs: List<CashuProof>,
        outputs: List<BlindedMessage>? = null
    ): MeltResponse = withContext(Dispatchers.IO) {
        val baseUrl = cleanUrl(mintUrl)
        val url = URL("$baseUrl/v1/melt/bolt11")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.setRequestProperty("Content-Type", "application/json")

        val inputsArray = JSONArray()
        inputs.forEach { proof ->
            inputsArray.put(JSONObject().apply {
                put("amount", proof.amount)
                put("id", proof.id)
                put("secret", proof.secret)
                put("C", proof.C)
            })
        }

        val outputsArray = JSONArray()
        outputs?.forEach { out ->
            outputsArray.put(JSONObject().apply {
                put("amount", out.amount)
                put("id", out.id)
                put("B_", out.B_)
            })
        }

        val payload = JSONObject().apply {
            put("quote", quoteId)
            put("inputs", inputsArray)
            put("outputs", outputsArray)
        }

        try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                
                val changeList = mutableListOf<BlindedSignature>()
                val changeArray = json.optJSONArray("change")
                if (changeArray != null) {
                    for (i in 0 until changeArray.length()) {
                        val obj = changeArray.getJSONObject(i)
                        changeList.add(
                            BlindedSignature(
                                amount = obj.getLong("amount"),
                                id = obj.getString("id"),
                                C_ = obj.getString("C_")
                            )
                        )
                    }
                }
                
                MeltResponse(
                    paid = json.optBoolean("paid", false),
                    preimage = json.optString("payment_preimage", null),
                    change = changeList
                )
            } else {
                val err = readErrorStream(connection)
                throw Exception("Failed to execute melt payment: HTTP $responseCode - $err")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun swapTokens(
        mintUrl: String,
        inputs: List<CashuProof>,
        outputs: List<BlindedMessage>
    ): List<BlindedSignature> = withContext(Dispatchers.IO) {
        val baseUrl = cleanUrl(mintUrl)
        val url = URL("$baseUrl/v1/swap")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Content-Type", "application/json")

        val inputsArray = JSONArray()
        inputs.forEach { proof ->
            inputsArray.put(JSONObject().apply {
                put("amount", proof.amount)
                put("id", proof.id)
                put("secret", proof.secret)
                put("C", proof.C)
            })
        }

        val outputsArray = JSONArray()
        outputs.forEach { out ->
            outputsArray.put(JSONObject().apply {
                put("amount", out.amount)
                put("id", out.id)
                put("B_", out.B_)
            })
        }

        val payload = JSONObject().apply {
            put("inputs", inputsArray)
            put("outputs", outputsArray)
        }

        try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                val sigsArray = json.getJSONArray("signatures")
                val signatures = mutableListOf<BlindedSignature>()
                
                for (i in 0 until sigsArray.length()) {
                    val obj = sigsArray.getJSONObject(i)
                    signatures.add(
                        BlindedSignature(
                            amount = obj.getLong("amount"),
                            id = obj.getString("id"),
                            C_ = obj.getString("C_")
                        )
                    )
                }
                signatures
            } else {
                val err = readErrorStream(connection)
                throw Exception("Failed to swap eCash tokens: HTTP $responseCode - $err")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readStream(connection: HttpURLConnection): String {
        return BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            reader.readText()
        }
    }

    private fun readErrorStream(connection: HttpURLConnection): String {
        val errorStream = connection.errorStream ?: return ""
        return BufferedReader(InputStreamReader(errorStream)).use { reader ->
            reader.readText()
        }
    }
}
