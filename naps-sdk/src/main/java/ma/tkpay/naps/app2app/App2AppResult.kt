package ma.tkpay.naps.app2app

import ma.tkpay.naps.models.Receipt

/**
 * Result of an App2App payment or reversal.
 *
 * When [printReceipt] was false, [merchantReceipt] and [customerReceipt] are populated
 * with ready-to-print thermal receipt strings. The developer decides whether to print
 * them via the Sunmi printer, store them electronically, send by email, etc.
 *
 * Card data is PCI DSS compliant: [maskedCardNumber] always contains only
 * first 6 + last 4 digits — the full PAN is never exposed.
 *
 * @property success         Whether the transaction was approved.
 * @property responseCode    3-digit NAPS response code ("000" = approved).
 * @property responseMessage Human-readable response message from NAPS Pay.
 * @property orderId         Order ID echoed back from NAPS Pay.
 * @property stan            System Trace Audit Number — use for reversals.
 * @property rrn             Retrieval Reference Number.
 * @property approvalCode    Authorization/approval code.
 * @property authorizationNumber Authorization number.
 * @property transactionNumber NAPS internal transaction number.
 * @property receiptNumber   Receipt number from NAPS Pay.
 * @property maskedCardNumber PCI DSS compliant: first 6 + last 4 only (e.g. "516794******3315").
 * @property cardScheme      Card network (e.g. "VISA", "MASTERCARD", "CMI").
 * @property cardNationality Card issuing country.
 * @property merchantId      Merchant ID registered with NAPS.
 * @property merchantName    Merchant name as registered.
 * @property merchantCity    Merchant city.
 * @property terminalId      Terminal ID.
 * @property transactionDate Transaction date (DDMMYYYY).
 * @property transactionTime Transaction time (HHMMSS).
 * @property merchantReceipt Merchant copy receipt — only populated when printReceipt=false.
 * @property customerReceipt Customer copy receipt — only populated when printReceipt=false.
 * @property isCancelled     True if the user cancelled in NAPS Pay.
 * @property errorType       Categorised error type on failure.
 * @property errorMessage    Developer-facing error description.
 */
data class App2AppResult(
    val success: Boolean,
    val responseCode: String,
    val responseMessage: String? = null,
    val orderId: String? = null,

    // Traceability
    val stan: String? = null,
    val rrn: String? = null,
    val approvalCode: String? = null,
    val authorizationNumber: String? = null,
    val transactionNumber: String? = null,
    val receiptNumber: String? = null,

    // Card data — PCI DSS compliant (masked by NAPS Pay before returning)
    val maskedCardNumber: String? = null,
    val cardScheme: String? = null,
    val cardNationality: String? = null,

    // Merchant / terminal info
    val merchantId: String? = null,
    val merchantName: String? = null,
    val merchantCity: String? = null,
    val terminalId: String? = null,

    // Timing
    val transactionDate: String? = null,
    val transactionTime: String? = null,

    // Receipts — populated only when printReceipt = false
    val merchantReceipt: Receipt? = null,
    val customerReceipt: Receipt? = null,

    // Error info
    val isCancelled: Boolean = false,
    val errorType: App2AppErrorType = App2AppErrorType.NONE,
    val errorMessage: String? = null
) {
    /** True if transaction was approved (responseCode "000" or "00"). */
    fun isApproved(): Boolean = success && (responseCode == "000" || responseCode == "00")

    /** Formatted card number for display. Returns "N/A" if not available. */
    fun getFormattedCardNumber(): String = maskedCardNumber ?: "N/A"

    companion object {
        internal fun approved(
            responseCode: String,
            responseMessage: String?,
            orderId: String?,
            stan: String?,
            rrn: String?,
            approvalCode: String?,
            authorizationNumber: String?,
            transactionNumber: String?,
            receiptNumber: String?,
            maskedCardNumber: String?,
            cardScheme: String?,
            cardNationality: String?,
            merchantId: String?,
            merchantName: String?,
            merchantCity: String?,
            terminalId: String?,
            transactionDate: String?,
            transactionTime: String?,
            merchantReceipt: Receipt?,
            customerReceipt: Receipt?
        ) = App2AppResult(
            success = true,
            responseCode = responseCode,
            responseMessage = responseMessage,
            orderId = orderId,
            stan = stan,
            rrn = rrn,
            approvalCode = approvalCode,
            authorizationNumber = authorizationNumber,
            transactionNumber = transactionNumber,
            receiptNumber = receiptNumber,
            maskedCardNumber = maskedCardNumber,
            cardScheme = cardScheme,
            cardNationality = cardNationality,
            merchantId = merchantId,
            merchantName = merchantName,
            merchantCity = merchantCity,
            terminalId = terminalId,
            transactionDate = transactionDate,
            transactionTime = transactionTime,
            merchantReceipt = merchantReceipt,
            customerReceipt = customerReceipt
        )

        internal fun cancelled() = App2AppResult(
            success = false,
            responseCode = App2AppConstants.ResponseCode.CANCELLED,
            isCancelled = true,
            errorType = App2AppErrorType.CANCELLED,
            errorMessage = "Transaction annulée par l'utilisateur."
        )

        internal fun failure(
            responseCode: String,
            responseMessage: String?,
            errorType: App2AppErrorType
        ) = App2AppResult(
            success = false,
            responseCode = responseCode,
            responseMessage = responseMessage,
            errorType = errorType,
            errorMessage = errorType.defaultMessage
        )

        internal fun notInstalled() = App2AppResult(
            success = false,
            responseCode = "998",
            errorType = App2AppErrorType.NOT_INSTALLED,
            errorMessage = App2AppErrorType.NOT_INSTALLED.defaultMessage
        )

        internal fun launchFailed(cause: String) = App2AppResult(
            success = false,
            responseCode = "997",
            errorType = App2AppErrorType.LAUNCH_FAILED,
            errorMessage = cause
        )
    }
}

/**
 * Categorised error types for App2App results.
 */
enum class App2AppErrorType(val defaultMessage: String) {
    NONE(""),
    CANCELLED("Transaction annulée."),
    DECLINED("Carte refusée. Veuillez essayer une autre carte."),
    INSUFFICIENT_FUNDS("Fonds insuffisants. Veuillez essayer une autre carte."),
    EXPIRED_CARD("Carte expirée. Veuillez utiliser une autre carte."),
    INVALID_PIN("PIN invalide. Veuillez réessayer."),
    PIN_TRIES_EXCEEDED("Nombre de tentatives PIN dépassé. Carte bloquée."),
    TIMEOUT("Délai d'attente dépassé. Veuillez réessayer."),
    SYSTEM_ERROR("Erreur système. Veuillez réessayer plus tard."),
    NOT_INSTALLED("Application NAPS Pay non installée."),
    LAUNCH_FAILED("Impossible de lancer NAPS Pay."),
    UNKNOWN("Une erreur s'est produite. Veuillez réessayer.")
}
