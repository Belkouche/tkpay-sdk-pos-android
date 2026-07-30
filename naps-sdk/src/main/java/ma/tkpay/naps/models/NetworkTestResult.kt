package ma.tkpay.naps.models

data class NetworkTestResult(
    val success: Boolean,
    val responseCode: String,
    val rttMs: Long? = null,
    val error: String? = null
)
