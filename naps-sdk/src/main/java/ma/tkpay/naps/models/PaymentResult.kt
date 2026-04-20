package ma.tkpay.naps.models

/**
 * Payment result containing transaction details
 *
 * @property success Whether payment was approved
 * @property responseCode NAPS Pay response code ("000" = approved)
 * @property stan System Trace Audit Number
 * @property maskedCardNumber Card number (masked: first 6 + last 4 digits)
 * @property cardExpiry Card expiry (YYMM format)
 * @property cardholderName Cardholder name (if available)
 * @property entryMode Entry mode (CC=Contactless, SC=Contact)
 * @property amount Transaction amount
 * @property authNumber Authorization number
 * @property ncai Register + Cashier identifier
 * @property sequence Sequence number used
 * @property transactionDate Transaction date (DDMMYYYY)
 * @property transactionTime Transaction time (HHMMSS)
 * @property merchantReceipt Merchant copy of receipt
 * @property customerReceipt Customer copy of receipt
 * @property error Error message if payment failed
 */
data class PaymentResult(
    val success: Boolean,
    val responseCode: String,
    val stan: String? = null,
    val maskedCardNumber: String? = null,
    val cardExpiry: String? = null,
    val cardholderName: String? = null,
    val entryMode: String? = null,
    val amount: Double? = null,
    val authNumber: String? = null,
    val ncai: String? = null,
    val sequence: String? = null,
    val transactionDate: String? = null,
    val transactionTime: String? = null,
    val merchantReceipt: Receipt? = null,
    val customerReceipt: Receipt? = null,
    val error: String? = null
) {
    /**
     * Check if payment was approved
     */
    fun isApproved(): Boolean = success && responseCode == "000"

    /**
     * Get formatted card number for display
     * Example: "516794******3315"
     */
    fun getFormattedCardNumber(): String = maskedCardNumber ?: "N/A"

    /**
     * Get formatted expiry for display
     * Example: "10/30" from "3010"
     */
    fun getFormattedExpiry(): String {
        return cardExpiry?.let {
            if (it.length == 4) {
                "${it.substring(2, 4)}/${it.substring(0, 2)}"
            } else it
        } ?: "N/A"
    }
}
