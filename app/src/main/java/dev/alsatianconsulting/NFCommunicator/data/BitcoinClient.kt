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
    private const val FALLBACK_URL = "https://blockstream.info/api"

    suspend fun fetchAddressInfo(address: String, index: Int): AddressInfo = withContext(Dispatchers.IO) {
        return@withContext try {
            fetchAddressInfoFrom(BASE_URL, address, index)
        } catch (primary: Exception) {
            android.util.Log.w("BitcoinClient", "mempool.space failed (${primary.message}), falling back to blockstream.info")
            fetchAddressInfoFrom(FALLBACK_URL, address, index)
        }
    }

    private fun fetchAddressInfoFrom(baseUrl: String, address: String, index: Int): AddressInfo {
        val balanceUrl = URL("$baseUrl/address/$address")
        val utxoUrl = URL("$baseUrl/address/$address/utxo")

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

        return AddressInfo(
            balance = balance,
            isUsed = fundedSum > 0,
            utxos = utxos
        )
    }

    suspend fun fetchBalance(address: String): Long = withContext(Dispatchers.IO) {
        return@withContext try {
            fetchBalanceFrom(BASE_URL, address)
        } catch (primary: Exception) {
            android.util.Log.w("BitcoinClient", "mempool.space failed (${primary.message}), falling back to blockstream.info")
            fetchBalanceFrom(FALLBACK_URL, address)
        }
    }

    private fun fetchBalanceFrom(baseUrl: String, address: String): Long {
        val url = URL("$baseUrl/address/$address")
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

                return (chainFunded + mempoolFunded) - (chainSpent + mempoolSpent)
            } else {
                throw Exception("Failed to fetch address balance: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun fetchUtxos(address: String): List<Utxo> = withContext(Dispatchers.IO) {
        return@withContext try {
            fetchUtxosFrom(BASE_URL, address)
        } catch (primary: Exception) {
            android.util.Log.w("BitcoinClient", "mempool.space failed (${primary.message}), falling back to blockstream.info")
            fetchUtxosFrom(FALLBACK_URL, address)
        }
    }

    private fun fetchUtxosFrom(baseUrl: String, address: String): List<Utxo> {
        val url = URL("$baseUrl/address/$address/utxo")
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
                return utxos
            } else {
                throw Exception("Failed to fetch UTXOs: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun broadcastTransaction(txHex: String): String = withContext(Dispatchers.IO) {
        return@withContext try {
            broadcastTransactionTo(BASE_URL, txHex)
        } catch (primary: Exception) {
            android.util.Log.w("BitcoinClient", "mempool.space broadcast failed (${primary.message}), falling back to blockstream.info")
            broadcastTransactionTo(FALLBACK_URL, txHex)
        }
    }

    private fun broadcastTransactionTo(baseUrl: String, txHex: String): String {
        val url = URL("$baseUrl/tx")
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
                return readStream(connection).trim()
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
