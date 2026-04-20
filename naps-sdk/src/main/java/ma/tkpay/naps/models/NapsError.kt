package ma.tkpay.naps.models

/**
 * NAPS Pay error with code and message
 */
class NapsError(
    val code: ErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    companion object {
        fun connectionFailed(cause: Throwable? = null) = NapsError(
            ErrorCode.CONNECTION_FAILED,
            "Failed to connect to NAPS Pay terminal",
            cause
        )

        fun timeout(cause: Throwable? = null) = NapsError(
            ErrorCode.TIMEOUT,
            "Request timeout",
            cause
        )

        fun paymentDeclined(responseCode: String) = NapsError(
            ErrorCode.PAYMENT_DECLINED,
            "Payment declined: ${getResponseMessage(responseCode)}"
        )

        fun invalidResponse(message: String) = NapsError(
            ErrorCode.INVALID_RESPONSE,
            message
        )

        fun terminalDown() = NapsError(
            ErrorCode.TERMINAL_DOWN,
            "Terminal or server is down"
        )

        fun transactionNotFound() = NapsError(
            ErrorCode.TRANSACTION_NOT_FOUND,
            "Transaction not found"
        )

        fun alreadyCancelled() = NapsError(
            ErrorCode.ALREADY_CANCELLED,
            "Transaction already cancelled"
        )

        private fun getResponseMessage(code: String): String = when (code) {
            "000" -> "Approved"
            "909" -> "Terminal or server is down"
            "302" -> "Transaction not found"
            "482" -> "Transaction already cancelled"
            "480" -> "Transaction cancelled"
            else -> "Error code: $code"
        }
    }
}

/**
 * Error codes
 */
enum class ErrorCode {
    CONNECTION_FAILED,
    TIMEOUT,
    PAYMENT_DECLINED,
    INVALID_RESPONSE,
    TERMINAL_DOWN,
    TRANSACTION_NOT_FOUND,
    ALREADY_CANCELLED,
    UNKNOWN_ERROR
}
