package ma.tkpay.naps.gateway

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.PaymentResult
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Transaction notification payload for TKPay gateway
 */
data class TransactionNotification(
    val terminalHost: String,
    val amount: Double,
    val currency: String,
    val responseCode: String,
    val stan: String?,
    val authNumber: String?,
    val maskedCardNumber: String?,
    val cardExpiry: String?,
    val entryMode: String?,
    val cardholderName: String?,
    val ncai: String?,
    val registerId: String?,
    val cashierId: String?,
    val sequence: String?,
    val transactionDate: String?,
    val transactionTime: String?,
    val success: Boolean,
    val errorMessage: String?,
    val sdkVersion: String,
    val platform: String,
    val timestamp: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("terminal_host", terminalHost)
            put("amount", amount)
            put("currency", currency)
            put("response_code", responseCode)
            put("stan", stan)
            put("auth_number", authNumber)
            put("masked_card_number", maskedCardNumber)
            put("card_expiry", cardExpiry)
            put("entry_mode", entryMode)
            put("cardholder_name", cardholderName)
            put("ncai", ncai)
            put("register_id", registerId)
            put("cashier_id", cashierId)
            put("sequence", sequence)
            put("transaction_date", transactionDate)
            put("transaction_time", transactionTime)
            put("success", success)
            put("error_message", errorMessage)
            put("sdk_version", sdkVersion)
            put("platform", platform)
            put("timestamp", timestamp)
        }
    }
}

/**
 * Gateway notifier for sending transaction data to TKPay backend
 */
internal object GatewayNotifier {

    /** SDK version */
    private const val SDK_VERSION = "1.0.0"

    /** Gateway URL (static, not configurable) */
    private const val GATEWAY_URL = "https://api.tkpay.ma"

    /** Gateway endpoint for transaction notifications */
    private const val NOTIFICATION_ENDPOINT = "/v1/transactions/notify"

    /** Connection timeout in milliseconds */
    private const val CONNECTION_TIMEOUT = 10000

    /** Read timeout in milliseconds */
    private const val READ_TIMEOUT = 10000

    private const val TAG = "TKPayNaps"

    /** Exception handler that silently catches all errors */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // Silently ignore - never affect payment flow
        Log.d(TAG, "Background notification failed: ${throwable.message}")
    }

    /** Background scope with SupervisorJob - failures don't propagate */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    /**
     * Send transaction notification to TKPay gateway
     * This is fire-and-forget - errors are logged but don't affect the payment flow
     * Runs in a completely isolated background coroutine
     */
    internal fun notifyTransaction(
        terminalHost: String,
        request: PaymentRequest,
        result: PaymentResult
    ) {
        try {
            val notification = TransactionNotification(
                terminalHost = terminalHost,
                amount = request.amount,
                currency = "MAD",
                responseCode = result.responseCode,
                stan = result.stan,
                authNumber = result.authNumber,
                maskedCardNumber = result.maskedCardNumber,
                cardExpiry = result.cardExpiry,
                entryMode = result.entryMode,
                cardholderName = result.cardholderName,
                ncai = result.ncai ?: request.getNcai(),
                registerId = request.registerId,
                cashierId = request.cashierId,
                sequence = result.sequence ?: request.sequence,
                transactionDate = result.transactionDate,
                transactionTime = result.transactionTime,
                success = result.success,
                errorMessage = result.error,
                sdkVersion = SDK_VERSION,
                platform = "android",
                timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
            )

            // Send notification asynchronously (fire-and-forget)
            // SupervisorJob ensures this never affects the caller
            scope.launch {
                sendNotification(notification)
            }
        } catch (e: Exception) {
            // Even if notification creation fails, don't affect payment flow
            Log.d(TAG, "Failed to create notification: ${e.message}")
        }
    }

    /**
     * Send the notification to the gateway
     * This method is completely isolated and will never throw or block the caller
     */
    private fun sendNotification(notification: TransactionNotification) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$GATEWAY_URL$NOTIFICATION_ENDPOINT")
            connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "TKPayNaps-Android/$SDK_VERSION")
                connectTimeout = CONNECTION_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
            }

            val jsonBody = notification.toJson().toString()

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = connection.responseCode

            Log.d(TAG, if (responseCode in 200..299) {
                "Transaction notification sent successfully"
            } else {
                "Gateway returned status: $responseCode"
            })

        } catch (e: Exception) {
            // Silently fail - never affect payment flow
            Log.d(TAG, "Failed to send notification: ${e.message}")
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // Ignore disconnect errors
            }
        }
    }
}
