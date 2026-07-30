package ma.tkpay.naps.models

data class ReferencingResult(
    val success: Boolean,
    val responseCode: String,
    val receipt: Receipt? = null,
    val error: String? = null
)
