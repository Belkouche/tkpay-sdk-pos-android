package ma.tkpay.naps.models

data class DuplicateReceiptResult(
    val success: Boolean,
    val responseCode: String,
    val merchantReceipt: Receipt? = null,
    val error: String? = null
)
