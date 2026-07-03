package dev.alsatianconsulting.NFCommunicator.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class Utxo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val confirmed: Boolean,
    val addressIndex: Int = 0,
    val address: String = ""
)

data class AddressInfo(
    val balance: Long,
    val isUsed: Boolean,
    val utxos: List<Utxo>
)

object BitcoinClient {
    private const val BASE_URL = "https://mempool.space/api"

    suspend fun fetchAddressInfo(address: String, index: Int): AddressInfo = withContext(Dispatchers.IO) {
        val balanceUrl = URL("$BASE_URL/address/$address")
        val utxoUrl = URL("$BASE_URL/address/$address/utxo")

        val balanceConn = balanceUrl.openConnection() as HttpURLConnection
        balanceConn.requestMethod = "GET"
        balanceConn.connectTimeout = 10000
        balanceConn.readTimeout = 10000

        var fundedSum = 0L
        var balance = 0L
        try {
            if (balanceConn.responseCode == 200) {
                val response = readStream(balanceConn)
                val json = JSONObject(response)
                val chainStats = json.optJSONObject("chain_stats")
                val mempoolStats = json.optJSONObject("mempool_stats")

                val chainFunded = chainStats?.optLong("funded_txo_sum") ?: 0L
                val chainSpent = chainStats?.optLong("spent_txo_sum") ?: 0L
                val mempoolFunded = mempoolStats?.optLong("funded_txo_sum") ?: 0L
                val mempoolSpent = mempoolStats?.optLong("spent_txo_sum") ?: 0L

                fundedSum = chainFunded + mempoolFunded
                balance = fundedSum - (chainSpent + mempoolSpent)
            } else {
                throw Exception("Failed to fetch address balance: HTTP ${balanceConn.responseCode}")
            }
        } finally {
            balanceConn.disconnect()
        }

        val utxoConn = utxoUrl.openConnection() as HttpURLConnection
        utxoConn.requestMethod = "GET"
        utxoConn.connectTimeout = 10000
        utxoConn.readTimeout = 10000

        val utxos = mutableListOf<Utxo>()
        try {
            if (utxoConn.responseCode == 200) {
                val response = readStream(utxoConn)
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val txid = obj.getString("txid")
                    val vout = obj.getInt("vout")
                    val value = obj.getLong("value")
                    val status = obj.optJSONObject("status")
                    val confirmed = status?.optBoolean("confirmed") ?: false
                    utxos.add(Utxo(txid, vout, value, confirmed, addressIndex = index, address = address))
                }
            } else {
                throw Exception("Failed to fetch UTXOs: HTTP ${utxoConn.responseCode}")
            }
        } finally {
            utxoConn.disconnect()
        }

        AddressInfo(
            balance = balance,
            isUsed = fundedSum > 0,
            utxos = utxos
        )
    }

    suspend fun fetchBalance(address: String): Long = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/address/$address")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            if (connection.responseCode == 200) {
                val response = readStream(connection)
                val json = JSONObject(response)
                
                val chainStats = json.optJSONObject("chain_stats")
                val mempoolStats = json.optJSONObject("mempool_stats")

                val chainFunded = chainStats?.optLong("funded_txo_sum") ?: 0L
                val chainSpent = chainStats?.optLong("spent_txo_sum") ?: 0L
                val mempoolFunded = mempoolStats?.optLong("funded_txo_sum") ?: 0L
                val mempoolSpent = mempoolStats?.optLong("spent_txo_sum") ?: 0L

                (chainFunded + mempoolFunded) - (chainSpent + mempoolSpent)
            } else {
                throw Exception("Failed to fetch address balance: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun fetchUtxos(address: String): List<Utxo> = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/address/$address/utxo")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            if (connection.responseCode == 200) {
                val response = readStream(connection)
                val jsonArray = JSONArray(response)
                val utxos = mutableListOf<Utxo>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val txid = obj.getString("txid")
                    val vout = obj.getInt("vout")
                    val value = obj.getLong("value")
                    val status = obj.optJSONObject("status")
                    val confirmed = status?.optBoolean("confirmed") ?: false
                    utxos.add(Utxo(txid, vout, value, confirmed))
                }
                utxos
            } else {
                throw Exception("Failed to fetch UTXOs: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun broadcastTransaction(txHex: String): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/tx")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Content-Type", "text/plain")

        try {
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(txHex)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                readStream(connection).trim()
            } else {
                val errorStream = connection.errorStream
                val errorMessage = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else {
                    "Unknown error"
                }
                throw Exception("Broadcast failed: HTTP $responseCode - $errorMessage")
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
}
