package ma.tkpay.naps.models

data class ResetResult(
    val success: Boolean,
    val responseCode: String,
    val error: String? = null
)
