package dev.alsatianconsulting.NFCommunicator.domain

import breez_sdk_liquid.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BreezManager {
    private var sdk: BindingLiquidSdk? = null

    suspend fun connect(
        mnemonic: String,
        apiKey: String,
        network: LiquidNetwork,
        workingDir: File
    ): BindingLiquidSdk = withContext(Dispatchers.IO) {
        disconnect()

        val config = defaultConfig(network, apiKey)
        config.workingDir = workingDir.absolutePath

        val connectRequest = ConnectRequest(config, mnemonic)
        val initializedSdk = connect(connectRequest)
        sdk = initializedSdk
        initializedSdk
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        sdk?.disconnect()
        sdk = null
    }

    fun getSdk(): BindingLiquidSdk? {
        return sdk
    }

    suspend fun getWalletInfo(): WalletInfo? = withContext(Dispatchers.IO) {
        sdk?.getInfo()?.walletInfo
    }

    suspend fun prepareReceive(amountSats: Long): PrepareReceiveResponse = withContext(Dispatchers.IO) {
        val currentSdk = sdk ?: throw IllegalStateException("Breez SDK is not connected.")
        val receiveAmount = ReceiveAmount.Bitcoin(amountSats.toULong())
        val request = PrepareReceiveRequest(
            paymentMethod = PaymentMethod.BOLT11_INVOICE,
            amount = receiveAmount
        )
        currentSdk.prepareReceivePayment(request)
    }

    suspend fun executeReceive(prepareResponse: PrepareReceiveResponse): ReceivePaymentResponse = withContext(Dispatchers.IO) {
        val currentSdk = sdk ?: throw IllegalStateException("Breez SDK is not connected.")
        val request = ReceivePaymentRequest(
            prepareResponse = prepareResponse,
            description = null,
            descriptionHash = null,
            payerNote = null
        )
        currentSdk.receivePayment(request)
    }

    suspend fun prepareSend(destination: String, amountSats: Long?): PrepareSendResponse = withContext(Dispatchers.IO) {
        val currentSdk = sdk ?: throw IllegalStateException("Breez SDK is not connected.")
        val payAmount = amountSats?.let { PayAmount.Bitcoin(it.toULong()) }
        val request = PrepareSendRequest(
            destination = destination,
            amount = payAmount
        )
        currentSdk.prepareSendPayment(request)
    }

    suspend fun executeSend(prepareResponse: PrepareSendResponse): SendPaymentResponse = withContext(Dispatchers.IO) {
        val currentSdk = sdk ?: throw IllegalStateException("Breez SDK is not connected.")
        val request = SendPaymentRequest(
            prepareResponse = prepareResponse,
            useAssetFees = null,
            payerNote = null
        )
        currentSdk.sendPayment(request)
    }
}
