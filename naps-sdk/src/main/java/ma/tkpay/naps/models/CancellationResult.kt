package ma.tkpay.naps.models

data class CancellationResult(
    val success: Boolean,
    val responseCode: String,
    val stan: String? = null,
    val error: String? = null
)
