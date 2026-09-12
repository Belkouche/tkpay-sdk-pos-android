package ma.tkpay.naps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.config.TransportType
import ma.tkpay.naps.connection.NapsConnection
import ma.tkpay.naps.connection.NapsTransport
import ma.tkpay.naps.connection.UsbSerialConnection
import ma.tkpay.naps.connection.use
import ma.tkpay.naps.gateway.GatewayNotifier
import ma.tkpay.naps.models.CancellationResult
import ma.tkpay.naps.models.DuplicateReceiptResult
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.NetworkTestResult
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.PaymentResult
import ma.tkpay.naps.models.Receipt
import ma.tkpay.naps.models.ReferencingResult
import ma.tkpay.naps.models.ResetResult
import ma.tkpay.naps.models.ReceiptType
import ma.tkpay.naps.models.SettlementResult
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
class NapsPayClient(
    private val config: NapsConfig,
    private val context: Context? = null
) {

    private val sequenceGenerator = AtomicInteger(1)

    /**
     * Build the transport for this client's [config].
     *
     * TCP is the default. For [TransportType.USB_SERIAL] a non-null [context] is
     * required (the USB stack needs it), so construct the client with a Context.
     */
    private fun newTransport(): NapsTransport = when (config.transport) {
        TransportType.TCP_SOCKET -> NapsConnection(config)
        TransportType.USB_SERIAL -> {
            val ctx = context
                ?: throw NapsError.connectionFailed(
                    IllegalStateException("USB serial transport requires a Context: use NapsPayClient(config, context)")
                )
            UsbSerialConnection(ctx, config)
        }
    }

    /** Best-effort label for gateway notifications: TCP host or serial device name. */
    private fun terminalLabel(): String = config.host ?: config.serialPortName ?: "usb-serial"

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
        val connection = newTransport()

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
                    terminalHost = terminalLabel(),
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
                terminalHost = terminalLabel(),
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
        connection: NapsTransport,
        request: PaymentRequest
    ): Map<String, String> {
        val sequence = request.sequence ?: generateSequence()

        val tlvRequest = TlvProtocol.buildPaymentRequest(
            amount = request.amount,
            ncai = request.getNcai(),
            sequence = sequence
        )

        val tlvResponse = connection.sendAndReceive(tlvRequest, timeout = config.timeout)
        return TlvProtocol.parse(tlvResponse)
    }

    /**
     * Send confirmation (Phase 2)
     */
    private suspend fun sendConfirmation(
        connection: NapsTransport,
        request: PaymentRequest,
        paymentResponse: Map<String, String>
    ): Map<String, String> {
        val stan = paymentResponse[TlvProtocol.Tags.STAN]
            ?: throw NapsError.invalidResponse("Missing STAN")

        val sequence = request.sequence ?: paymentResponse[TlvProtocol.Tags.NS]
            ?: throw NapsError.invalidResponse("Missing sequence")

        // Thread the coherence fields from the approved Payment Response (TM=101)
        // into the confirmation (TM=002) per NAPS TLV v2 §4.2. MT/DE come from
        // the request amount; NCAR/DAEX are echoed from the response when present.
        val amountCentimes = (request.amount * 100).toLong()
        val ncar = paymentResponse[TlvProtocol.Tags.NCAR]
        val daex = paymentResponse[TlvProtocol.Tags.DAEX]

        val tlvConfirm = TlvProtocol.buildConfirmationRequest(
            stan = stan,
            ncai = request.getNcai(),
            sequence = sequence,
            amountCentimes = amountCentimes,
            ncar = ncar,
            daex = daex
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
        val errorMessage = TlvProtocol.describeResponse(responseCode)

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
     * Force end-of-day settlement (telecollecte) — TM=010
     *
     * Sends batch totals to the NAPS server. The terminal must be idle
     * ("Attente Caisse") and referencing must have run at least once.
     *
     * @param registerId Register ID (2 digits, e.g., "01")
     * @param cashierId Cashier ID (5 digits, e.g., "00001")
     * @return SettlementResult with response code
     */
    suspend fun settlement(registerId: String, cashierId: String): SettlementResult = withContext(Dispatchers.IO) {
        val ncai = registerId + cashierId
        val connection = newTransport()

        try {
            connection.connect()
            val tlvRequest = TlvProtocol.buildSettlementRequest(ncai)
            val tlvResponse = connection.sendAndReceive(tlvRequest, timeout = config.timeout)
            val fields = TlvProtocol.parse(tlvResponse)

            val responseCode = fields[TlvProtocol.Tags.CR] ?: ""
            SettlementResult(
                success = responseCode == "000",
                responseCode = responseCode,
                date = fields[TlvProtocol.Tags.DA],
                time = fields[TlvProtocol.Tags.HE],
                error = if (responseCode != "000") "Settlement failed with code: $responseCode" else null
            )
        } catch (e: Exception) {
            SettlementResult(success = false, responseCode = "", error = e.message)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Test connection to terminal (TCP-level only)
     *
     * @return true if connection successful, false otherwise
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val connection = newTransport()
            connection.use {
                connection.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Send a network test message to the terminal (TM=009)
     *
     * Unlike testConnection() which only opens a TCP socket,
     * this sends the actual NAPS M2M network test message and
     * verifies the terminal responds RC=000.
     *
     * @param registerId Register ID (2 digits)
     * @param cashierId Cashier ID (5 digits)
     */
    suspend fun networkTest(registerId: String, cashierId: String): NetworkTestResult = withContext(Dispatchers.IO) {
        val ncai = registerId + cashierId
        val connection = newTransport()
        val startMs = System.currentTimeMillis()

        try {
            connection.connect()
            val tlvRequest = TlvProtocol.buildNetworkTestRequest(ncai)
            val tlvResponse = connection.sendAndReceive(tlvRequest, timeout = 10_000)
            val fields = TlvProtocol.parse(tlvResponse)
            val responseCode = fields[TlvProtocol.Tags.CR] ?: ""

            NetworkTestResult(
                success = responseCode == "000",
                responseCode = responseCode,
                rttMs = System.currentTimeMillis() - startMs,
                error = if (responseCode != "000") "Network test failed with code: $responseCode" else null
            )
        } catch (e: Exception) {
            NetworkTestResult(success = false, responseCode = "", error = e.message)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Cancel (void) a previous transaction — two-phase flow (TM=003 → TM=004)
     *
     * NapsPay v5.4.4+ requires [amountCentimes] (TAG 002) in the TM=003 frame.
     * Flow on a single TCP connection:
     *   1. Send TM=003 → terminal replies MT=103 (CR=000 = accepted)
     *   2. Send TM=004 echoing the amount from MT=103 → terminal replies
     *      MT=104 with the final result (CR=000/995 = success)
     *
     * @param stan STAN of the transaction to cancel
     * @param registerId Register ID (2 digits)
     * @param cashierId Cashier ID (5 digits)
     * @param sequence Sequence number of the original transaction
     * @param amountCentimes Original amount in centimes (required for v5.4.4+)
     */
    suspend fun cancelPayment(
        stan: String,
        registerId: String,
        cashierId: String,
        sequence: String,
        amountCentimes: Long? = null
    ): CancellationResult = withContext(Dispatchers.IO) {
        val ncai = registerId + cashierId
        val connection = newTransport()

        try {
            connection.connect()

            // Phase 1: TM=003 → MT=103
            val tlvRequest = TlvProtocol.buildCancellationRequest(stan, ncai, sequence, amountCentimes)
            val tlvResponse = connection.sendAndReceive(tlvRequest, timeout = config.timeout)
            val fields = TlvProtocol.parse(tlvResponse)
            val responseCode = fields[TlvProtocol.Tags.CR] ?: ""
            val mt = fields[TlvProtocol.Tags.TM] ?: ""

            // MT=104 direct means already finalised (single-phase firmware)
            if (mt == TlvProtocol.MessageTypes.CANCELLATION_CORRECTION) {
                return@withContext CancellationResult(
                    success = responseCode == "000",
                    responseCode = responseCode,
                    stan = fields[TlvProtocol.Tags.STAN],
                    error = if (responseCode != "000") "Cancellation failed with code: $responseCode" else null
                )
            }

            if (mt != TlvProtocol.MessageTypes.CANCELLATION_RESPONSE || responseCode != "000") {
                return@withContext CancellationResult(
                    success = false,
                    responseCode = responseCode,
                    stan = fields[TlvProtocol.Tags.STAN],
                    error = "Cancellation failed with code: $responseCode"
                )
            }

            // Phase 2: TM=004 on the same connection, echoing the amount from MT=103
            val confirmRequest = TlvProtocol.buildCancellationConfirmation(
                fields[TlvProtocol.Tags.STAN] ?: stan,
                ncai,
                sequence,
                fields[TlvProtocol.Tags.MT]
            )
            val confirmResponse = connection.sendAndReceive(confirmRequest, timeout = config.confirmationTimeout)
            val fields2 = TlvProtocol.parse(confirmResponse)
            val cr2 = fields2[TlvProtocol.Tags.CR] ?: ""
            // CR=000 = confirmed, CR=995 = host processing (also success)
            val success2 = cr2 == "000" || cr2 == "995"

            CancellationResult(
                success = success2,
                responseCode = cr2,
                stan = fields2[TlvProtocol.Tags.STAN] ?: fields[TlvProtocol.Tags.STAN],
                error = if (!success2) "Cancellation failed with code: $cr2" else null
            )
        } catch (e: Exception) {
            CancellationResult(success = false, responseCode = "", error = e.message)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Request a duplicate receipt from the terminal (TM=008)
     *
     * Reprints the last transaction receipt. Pass stan to reprint a specific
     * transaction; omit to reprint the last one.
     *
     * @param registerId Register ID (2 digits)
     * @param cashierId Cashier ID (5 digits)
     * @param stan Optional STAN of the transaction to reprint
     */
    suspend fun printDuplicate(
        registerId: String,
        cashierId: String,
        stan: String? = null
    ): DuplicateReceiptResult = withContext(Dispatchers.IO) {
        val ncai = registerId + cashierId
        val connection = newTransport()

        try {
            connection.connect()
            val tlvRequest = TlvProtocol.buildDuplicateRequest(ncai, stan)
            val tlvResponse = connection.sendAndReceive(tlvRequest, timeout = config.timeout)
            val fields = TlvProtocol.parse(tlvResponse)
            val responseCode = fields[TlvProtocol.Tags.CR] ?: ""
            val merchantReceipt = fields[TlvProtocol.Tags.DP]?.let {
                ReceiptParser.parse(it, ReceiptType.MERCHANT)
            }

            DuplicateReceiptResult(
                success = responseCode == "000",
                responseCode = responseCode,
                merchantReceipt = merchantReceipt,
                error = if (responseCode != "000") "Duplicate receipt failed with code: $responseCode" else null
            )
        } catch (e: Exception) {
            DuplicateReceiptResult(success = false, responseCode = "", error = e.message)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reset the terminal PinPAD (TM=012)
     *
     * Sends the terminal back to idle ("Attente Caisse") state.
     * Use if the terminal is stuck in card-waiting mode.
     *
     * @param registerId Register ID (2 digits)
     * @param cashierId Cashier ID (5 digits)
     */
    suspend fun resetPinPad(registerId: String, cashierId: String): ResetResult = withContext(Dispatchers.IO) {
        val ncai = registerId + cashierId
        val connection = newTransport()

        try {
            connection.connect()
            val tlvRequest = TlvProtocol.buildResetRequest(ncai)
            val tlvResponse = connection.sendAndReceive(tlvRequest, timeout = 10_000)
            val fields = TlvProtocol.parse(tlvResponse)
            val responseCode = fields[TlvProtocol.Tags.CR] ?: ""

            ResetResult(
                success = responseCode == "000",
                responseCode = responseCode,
                error = if (responseCode != "000") "Reset failed with code: $responseCode" else null
            )
        } catch (e: Exception) {
            ResetResult(success = false, responseCode = "", error = e.message)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Run terminal referencing / configuration sync (TM=013)
     *
     * Must be called at least once before the first payment to download
     * merchant config from the NAPS server.
     *
     * @param registerId Register ID (2 digits)
     * @param cashierId Cashier ID (5 digits)
     */
    suspend fun referencing(registerId: String, cashierId: String): ReferencingResult = withContext(Dispatchers.IO) {
        val ncai = registerId + cashierId
        val connection = newTransport()

        try {
            connection.connect()
            val tlvRequest = TlvProtocol.buildReferencingRequest(ncai)
            val tlvResponse = connection.sendAndReceive(tlvRequest, timeout = config.timeout)
            val fields = TlvProtocol.parse(tlvResponse)
            val responseCode = fields[TlvProtocol.Tags.CR] ?: ""
            val receipt = fields[TlvProtocol.Tags.DP]?.let {
                ReceiptParser.parse(it, ReceiptType.MERCHANT)
            }

            ReferencingResult(
                success = responseCode == "000",
                responseCode = responseCode,
                receipt = receipt,
                error = if (responseCode != "000") "Referencing failed with code: $responseCode" else null
            )
        } catch (e: Exception) {
            ReferencingResult(success = false, responseCode = "", error = e.message)
        } finally {
            connection.disconnect()
        }
    }
}
