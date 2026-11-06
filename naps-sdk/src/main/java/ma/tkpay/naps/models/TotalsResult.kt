package ma.tkpay.naps.models

/**
 * Totals report result for TM=010
 *
 * @property success Whether the terminal returned the totals ("000")
 * @property responseCode NAPS Pay response code
 * @property receipt Totals receipt printed by the terminal (DP/010), if any
 * @property error Error message if the request failed
 */
data class TotalsResult(
    val success: Boolean,
    val responseCode: String,
    val receipt: Receipt? = null,
    val error: String? = null
)
