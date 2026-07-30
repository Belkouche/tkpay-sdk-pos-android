package ma.tkpay.naps.models

/**
 * Settlement result for TM=010 (telecollecte)
 *
 * @property success Whether settlement was accepted by NAPS server
 * @property responseCode NAPS Pay response code ("000" = success)
 * @property date Settlement date (DDMMYYYY)
 * @property time Settlement time (HHMMSS)
 * @property error Error message if settlement failed
 */
data class SettlementResult(
    val success: Boolean,
    val responseCode: String,
    val date: String? = null,
    val time: String? = null,
    val error: String? = null
)
