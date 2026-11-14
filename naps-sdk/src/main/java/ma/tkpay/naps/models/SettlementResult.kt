package ma.tkpay.naps.models

/**
 * Result of the deprecated [ma.tkpay.naps.NapsPayClient.settlement]: always `"UNSUPPORTED"`,
 * as the ECR protocol has no telecollecte message.
 */
data class SettlementResult(
    val success: Boolean,
    val responseCode: String,
    val date: String? = null,
    val time: String? = null,
    val error: String? = null
)
