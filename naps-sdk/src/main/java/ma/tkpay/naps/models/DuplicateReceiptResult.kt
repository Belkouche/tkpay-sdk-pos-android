package ma.tkpay.naps.models

data class DuplicateReceiptResult(
    val success: Boolean,
    val responseCode: String,
    /** STAN (008) of the transaction the terminal found: the one asked for, or the last recorded one. */
    val stan: String? = null,
    val merchantReceipt: Receipt? = null,
    val error: String? = null
)
