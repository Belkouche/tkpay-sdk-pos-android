package ma.tkpay.naps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.connection.NapsConnection
import ma.tkpay.naps.gateway.GatewayNotifier
import ma.tkpay.naps.models.*
import ma.tkpay.naps.protocol.ReceiptParser
import ma.tkpay.naps.protocol.TlvProtocol
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main client for NAPS Pay M2M integration
 *
 * Usage:
 * ```kotlin
 * val config = NapsConfig(host = "192.168.24.214")
 * val client = NapsPayClient(config)
 *
 * try {
 *     val result = client.processPayment(
 *         PaymentRequest(
 *             amount = 100.0,
 *             registerId = "01",
 *             cashierId = "00001"
 *         )
 *     )
 *
 *     if (result.isApproved()) {
 *         println("Payment approved!")
 *         println("STAN: ${result.stan}")
 *         println("Card: ${result.getFormattedCardNumber()}")
 *     } else {
 *         println("Payment failed: ${result.error}")
 *     }
 * } catch (e: NapsError) {
 *     println("Error: ${e.message}")
 * }
 * ```
 */
class NapsPayClient(private val config: NapsConfig) {

    private val sequenceGenerator = AtomicInteger(1)

    /**
     * Process a payment transaction
     *
     * This performs the complete two-phase payment flow:
     * 1. Send payment request → Customer taps card
     * 2. Send confirmation → Transaction complete
     *
     * @param request Payment request data
     * @return PaymentResult with transaction details
     * @throws NapsError if payment fails
     */
    suspend fun processPayment(request: PaymentRequest): PaymentResult = withContext(Dispatchers.IO) {
        val connection = NapsConnection(config)

        try {
            connection.connect()

            // Phase 1: Payment Request
            val paymentResponse = sendPaymentRequest(connection, request)

            // Check if payment was approved
            val responseCode = paymentResponse[TlvProtocol.Tags.CR]
                ?: throw NapsError.invalidResponse("Missing response code")

            if (responseCode != "000") {
                val result = buildFailedResult(responseCode, paymentResponse)
                // Send notification to gateway
                GatewayNotifier.notifyTransaction(
                    terminalHost = config.host,
                    request = request,
                    result = result
                )
                return@withContext result
            }

            // Phase 2: Confirmation (must be on same connection, within 40 seconds)
            val confirmationResponse = sendConfirmation(
                connection,
                request,
                paymentResponse
            )

            // Build successful result
            val result = buildSuccessfulResult(confirmationResponse)

            // Send notification to gateway
            GatewayNotifier.notifyTransaction(
                terminalHost = config.host,
                request = request,
                result = result
            )

            result
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Send payment request (Phase 1)
     */
    private suspend fun sendPaymentRequest(
        connection: NapsConnection,
        request: PaymentRequest
    ): Map<String, String> {
        val sequence = request.sequence ?: generateSequence()

        val tlvRequest = TlvProtocol.buildPaymentRequest(
            amount = request.amount,
            ncai = request.getNcai(),
            sequence = sequence
        )

        val tlvResponse = connection.sendAndReceive(tlvRequest)
        return TlvProtocol.parse(tlvResponse)
    }

    /**
     * Send confirmation (Phase 2)
     */
    private suspend fun sendConfirmation(
        connection: NapsConnection,
        request: PaymentRequest,
        paymentResponse: Map<String, String>
    ): Map<String, String> {
        val stan = paymentResponse[TlvProtocol.Tags.STAN]
            ?: throw NapsError.invalidResponse("Missing STAN")

        val sequence = request.sequence ?: paymentResponse[TlvProtocol.Tags.NS]
            ?: throw NapsError.invalidResponse("Missing sequence")

        val tlvConfirm = TlvProtocol.buildConfirmationRequest(
            stan = stan,
            ncai = request.getNcai(),
            sequence = sequence
        )

        val tlvResponse = connection.sendAndReceive(
            tlvConfirm,
            timeout = config.confirmationTimeout
        )

        return TlvProtocol.parse(tlvResponse)
    }

    /**
     * Build successful payment result
     */
    private fun buildSuccessfulResult(fields: Map<String, String>): PaymentResult {
        // Parse receipts
        val merchantReceipt = fields[TlvProtocol.Tags.DP]?.let { dpValue ->
            // Merchant receipt is typically first in the data
            ReceiptParser.parse(dpValue, ReceiptType.MERCHANT)
        }

        // In NAPS Pay, customer receipt is often a separate DP tag or part of same data
        // For now, we'll create both from same data
        val customerReceipt = fields[TlvProtocol.Tags.DP]?.let { dpValue ->
            ReceiptParser.parse(dpValue, ReceiptType.CUSTOMER)
        }

        return PaymentResult(
            success = true,
            responseCode = fields[TlvProtocol.Tags.CR] ?: "000",
            stan = fields[TlvProtocol.Tags.STAN],
            maskedCardNumber = fields[TlvProtocol.Tags.NCAR],
            cardExpiry = fields[TlvProtocol.Tags.DAEX],         // 017: Expiration Date (was DV/014)
            cardholderName = fields[TlvProtocol.Tags.NPRT],     // 016: Cardholder Name (was NC/018)
            entryMode = fields[TlvProtocol.Tags.EM],            // 040: Entry Mode (was SH/015)
            amount = null,  // Amount is in request, not response
            authNumber = fields[TlvProtocol.Tags.NA],
            ncai = fields[TlvProtocol.Tags.NCAI],
            sequence = fields[TlvProtocol.Tags.NS],
            transactionDate = fields[TlvProtocol.Tags.DATR],    // 018: Transaction Date (was DT/016)
            transactionTime = fields[TlvProtocol.Tags.HETR],    // 019: Transaction Time (was HT/017)
            merchantReceipt = merchantReceipt,
            customerReceipt = customerReceipt,
            error = null
        )
    }

    /**
     * Build failed payment result
     */
    private fun buildFailedResult(
        responseCode: String,
        fields: Map<String, String>
    ): PaymentResult {
        val errorMessage = when (responseCode) {
            "909" -> "Terminal or server is down"
            "302" -> "Transaction not found"
            "482" -> "Transaction already cancelled"
            "480" -> "Transaction cancelled"
            else -> "Payment declined with code: $responseCode"
        }

        return PaymentResult(
            success = false,
            responseCode = responseCode,
            stan = fields[TlvProtocol.Tags.STAN],
            error = errorMessage
        )
    }

    /**
     * Generate sequence number (6 digits)
     */
    private fun generateSequence(): String {
        val seq = sequenceGenerator.getAndIncrement()
        if (seq > 999999) {
            sequenceGenerator.set(1)
        }
        return seq.toString().padStart(6, '0')
    }

    /**
     * Test connection to terminal
     *
     * @return true if connection successful, false otherwise
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val connection = NapsConnection(config)
            connection.use {
                // Just test connection open/close
                connection.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }
}
