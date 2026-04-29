package ma.tkpay.naps.app2app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToLong

/**
 * NAPS Pay App-to-App client for Android.
 *
 * Launches the NAPS Pay app (com.m2mgroup.napspay) via Android Intent and
 * returns the result through an [ActivityResultLauncher]. This is the
 * recommended integration mode for Sunmi POS terminals where both apps
 * run on the same device.
 *
 * ## Setup — call once in your Activity's onCreate()
 * ```kotlin
 * val napsClient = App2AppClient.register(
 *     activity = this,
 *     onResult = { result ->
 *         if (result.isApproved()) {
 *             // Payment approved
 *             println("STAN: ${result.stan}")
 *             println("Card: ${result.getFormattedCardNumber()}")
 *
 *             // If printReceipt = false, receipts are ready:
 *             result.merchantReceipt?.let { printer.print(it) }
 *             result.customerReceipt?.let { storage.save(it) }
 *         }
 *     }
 * )
 * ```
 *
 * ## Payment
 * ```kotlin
 * napsClient.pay(
 *     orderId = "00000042",
 *     amountCentimes = 15000L,   // 150.00 MAD
 *     printReceipt = false       // SDK builds merchant + customer receipts
 * )
 * ```
 *
 * ## Reversal
 * ```kotlin
 * napsClient.reverse(
 *     originalStan = "001234",
 *     amountCentimes = 15000L
 * )
 * ```
 *
 * ## Important
 * - **Do NOT** use `FLAG_ACTIVITY_NEW_TASK` on the intent — it breaks
 *   `ActivityResultLauncher` and the result will never be delivered.
 * - Must be registered in [AppCompatActivity.onCreate] before the activity starts.
 * - Only one payment can be in-flight at a time.
 */
class App2AppClient private constructor(
    private val context: Context,
    private val launcher: ActivityResultLauncher<Intent>,
    private val onResult: (App2AppResult) -> Unit
) {

    private var pendingOrderId: String? = null
    private var pendingAmountCentimes: Long = 0L
    private var pendingPrintReceipt: Boolean = true

    companion object {
        private const val TAG = "App2AppClient"

        /**
         * Register the App2App client. Must be called in [AppCompatActivity.onCreate].
         *
         * @param activity   The host activity — must be [AppCompatActivity].
         * @param onResult   Callback invoked on the main thread with the payment result.
         * @return           Configured [App2AppClient] ready to call [pay] or [reverse].
         */
        fun register(
            activity: AppCompatActivity,
            onResult: (App2AppResult) -> Unit
        ): App2AppClient {
            lateinit var client: App2AppClient

            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { activityResult: ActivityResult ->
                client.handleActivityResult(activityResult)
            }

            client = App2AppClient(
                context = activity.applicationContext,
                launcher = launcher,
                onResult = onResult
            )

            return client
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Check whether NAPS Pay is installed on this device.
     */
    fun isNapsPayInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(App2AppConstants.PACKAGE_NAME, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Launch NAPS Pay for a card payment.
     *
     * @param orderId          Unique order identifier (max 20 chars, digits only recommended).
     * @param amountCentimes   Amount in centimes (e.g. 15000 = 150.00 MAD).
     * @param printReceipt     If true, NAPS Pay prints its own receipt on the terminal.
     *                         If false, the SDK builds and returns [App2AppResult.merchantReceipt]
     *                         and [App2AppResult.customerReceipt] — developer decides what to do
     *                         with them (print, store, email, etc.).
     */
    fun pay(
        orderId: String,
        amountCentimes: Long,
        printReceipt: Boolean = true
    ) {
        require(amountCentimes > 0) { "Amount must be positive" }

        if (!isNapsPayInstalled()) {
            onResult(App2AppResult.notInstalled())
            return
        }

        pendingOrderId = orderId
        pendingAmountCentimes = amountCentimes
        pendingPrintReceipt = printReceipt

        val intent = Intent().apply {
            component = ComponentName(App2AppConstants.PACKAGE_NAME, App2AppConstants.ACTIVITY_CLASS)
            // NOTE: Do NOT add FLAG_ACTIVITY_NEW_TASK — breaks ActivityResultLauncher
            putExtra(App2AppConstants.Request.REQUEST_CODE, App2AppConstants.REQUEST_AUTHORIZATION)
            putExtra(App2AppConstants.Request.AMOUNT, amountCentimes.toString())
            putExtra(App2AppConstants.Request.ORDER_ID, orderId)
            putExtra(App2AppConstants.Request.ORDER_TYPE, App2AppConstants.ORDER_TYPE_AUTHORIZATION)
            putExtra(
                App2AppConstants.Request.PRINTING,
                if (printReceipt) App2AppConstants.PRINTING_ENABLED else App2AppConstants.PRINTING_DISABLED
            )
        }

        try {
            launcher.launch(intent)
            Log.d(TAG, "Launched NAPS Pay for payment: orderId=$orderId, amount=$amountCentimes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch NAPS Pay: ${e.message}", e)
            pendingOrderId = null
            onResult(App2AppResult.launchFailed(e.message ?: "Unknown launch error"))
        }
    }

    /**
     * Launch NAPS Pay for a reversal (void) of a previous transaction.
     *
     * @param originalStan     The STAN returned in the original [App2AppResult.stan].
     * @param amountCentimes   Original amount in centimes (optional, for reference).
     * @param orderId          Original order ID (optional, for reference).
     * @param printReceipt     Whether NAPS Pay should print its own reversal receipt.
     *                         If false, receipts are built by the SDK and returned in the result.
     */
    fun reverse(
        originalStan: String,
        amountCentimes: Long? = null,
        orderId: String? = null,
        printReceipt: Boolean = true
    ) {
        if (!isNapsPayInstalled()) {
            onResult(App2AppResult.notInstalled())
            return
        }

        pendingOrderId = orderId
        pendingAmountCentimes = amountCentimes ?: 0L
        pendingPrintReceipt = printReceipt

        val intent = Intent().apply {
            component = ComponentName(App2AppConstants.PACKAGE_NAME, App2AppConstants.ACTIVITY_CLASS)
            putExtra(App2AppConstants.Request.REQUEST_CODE, App2AppConstants.REQUEST_REVERSAL)
            putExtra(App2AppConstants.Response.STAN, originalStan)
            orderId?.let { putExtra(App2AppConstants.Request.ORDER_ID, it) }
            amountCentimes?.let { putExtra(App2AppConstants.Request.AMOUNT, it.toString()) }
            putExtra(
                App2AppConstants.Request.PRINTING,
                if (printReceipt) App2AppConstants.PRINTING_ENABLED else App2AppConstants.PRINTING_DISABLED
            )
        }

        try {
            launcher.launch(intent)
            Log.d(TAG, "Launched NAPS Pay for reversal: stan=$originalStan")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch NAPS Pay reversal: ${e.message}", e)
            pendingOrderId = null
            onResult(App2AppResult.launchFailed(e.message ?: "Unknown launch error"))
        }
    }

    // -------------------------------------------------------------------------
    // Internal result handling
    // -------------------------------------------------------------------------

    private fun handleActivityResult(activityResult: ActivityResult) {
        val data = activityResult.data

        Log.d(TAG, "handleActivityResult: resultCode=${activityResult.resultCode}, hasData=${data != null}")

        // User pressed back / cancelled in NAPS Pay
        if (activityResult.resultCode == Activity.RESULT_CANCELED || data == null) {
            Log.d(TAG, "handleActivityResult: cancelled or no data")
            pendingOrderId = null
            onResult(App2AppResult.cancelled())
            return
        }

        // Log all extras for debugging
        data.extras?.keySet()?.forEach { key ->
            Log.d(TAG, "  extra: $key = ${data.extras?.get(key)}")
        }

        val responseCode = data.getStringExtra(App2AppConstants.Response.RESPONSE_CODE) ?: "999"
        val responseMessage = data.getStringExtra(App2AppConstants.Response.RESPONSE_MESSAGE)
        val isSuccess = responseCode == App2AppConstants.ResponseCode.SUCCESS ||
                responseCode == App2AppConstants.ResponseCode.SUCCESS_ALT

        if (!isSuccess) {
            Log.d(TAG, "handleActivityResult: failure, code=$responseCode")
            pendingOrderId = null
            onResult(App2AppResult.failure(
                responseCode = responseCode,
                responseMessage = responseMessage,
                errorType = mapErrorType(responseCode)
            ))
            return
        }

        // Parse all response fields
        val maskedCard = data.getStringExtra(App2AppConstants.Response.CARD_NUMBER)
        val cardScheme = data.getStringExtra(App2AppConstants.Response.CARD_SCHEME)
        val approvalCode = data.getStringExtra(App2AppConstants.Response.APPROVAL_CODE)
        val stan = data.getStringExtra(App2AppConstants.Response.STAN)
        val rrn = data.getStringExtra(App2AppConstants.Response.RRN)
        val receiptNumber = data.getStringExtra(App2AppConstants.Response.RECEIPT_NUMBER)
        val merchantName = data.getStringExtra(App2AppConstants.Response.MERCHANT_NAME)
        val merchantCity = data.getStringExtra(App2AppConstants.Response.MERCHANT_CITY)
        val terminalId = data.getStringExtra(App2AppConstants.Response.TERMINAL_ID)
        val transactionDate = data.getStringExtra(App2AppConstants.Response.TRANSACTION_DATE)
        val transactionTime = data.getStringExtra(App2AppConstants.Response.TRANSACTION_TIME)

        // Build receipts if developer requested no-print mode
        val (merchantReceipt, customerReceipt) = if (!pendingPrintReceipt) {
            App2AppReceiptBuilder.build(
                amount = pendingAmountCentimes,
                maskedCard = maskedCard,
                cardScheme = cardScheme,
                approvalCode = approvalCode,
                stan = stan,
                rrn = rrn,
                receiptNumber = receiptNumber,
                merchantName = merchantName,
                merchantCity = merchantCity,
                terminalId = terminalId,
                transactionDate = transactionDate,
                transactionTime = transactionTime
            )
        } else {
            null to null
        }

        val result = App2AppResult.approved(
            responseCode = responseCode,
            responseMessage = responseMessage,
            orderId = data.getStringExtra(App2AppConstants.Response.ORDER_ID) ?: pendingOrderId,
            stan = stan,
            rrn = rrn,
            approvalCode = approvalCode,
            authorizationNumber = data.getStringExtra(App2AppConstants.Response.AUTHORIZATION_NUMBER),
            transactionNumber = data.getStringExtra(App2AppConstants.Response.TRANSACTION_NUMBER),
            receiptNumber = receiptNumber,
            maskedCardNumber = maskedCard,
            cardScheme = cardScheme,
            cardNationality = data.getStringExtra(App2AppConstants.Response.CARD_NATIONALITY),
            merchantId = data.getStringExtra(App2AppConstants.Response.MERCHANT_ID),
            merchantName = merchantName,
            merchantCity = merchantCity,
            terminalId = terminalId,
            transactionDate = transactionDate,
            transactionTime = transactionTime,
            merchantReceipt = merchantReceipt,
            customerReceipt = customerReceipt
        )

        Log.d(TAG, "handleActivityResult: approved, stan=$stan, card=${maskedCard}")
        pendingOrderId = null
        onResult(result)
    }

    private fun mapErrorType(responseCode: String): App2AppErrorType {
        val normalized = responseCode.trimStart('0').padStart(2, '0')
        return when {
            responseCode == App2AppConstants.ResponseCode.CANCELLED -> App2AppErrorType.CANCELLED
            normalized == "05" || normalized == "12" || normalized == "14"
                    || normalized == "57" || normalized == "61" || normalized == "62" -> App2AppErrorType.DECLINED
            normalized == "51" -> App2AppErrorType.INSUFFICIENT_FUNDS
            normalized == "54" -> App2AppErrorType.EXPIRED_CARD
            normalized == "55" -> App2AppErrorType.INVALID_PIN
            normalized == "75" -> App2AppErrorType.PIN_TRIES_EXCEEDED
            normalized == "91" -> App2AppErrorType.TIMEOUT
            normalized == "96" -> App2AppErrorType.SYSTEM_ERROR
            else -> App2AppErrorType.UNKNOWN
        }
    }
}
