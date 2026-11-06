package ma.tkpay.naps.models

/**
 * Cancellation (void) result for TM=003 → TM=004
 *
 * @property success True only when the terminal confirmed the reversal (TM=104 CR=000/480)
 * @property responseCode NAPS Pay response code of the last reply ("" when none arrived)
 * @property stan STAN — for a [pending] void, the STAN of the transaction to cancel
 * @property error Error message when not successful
 * @property pending The reversal may or may not have executed (no TM=104 reply, or CR=995).
 *   Never treat as success: verify with [ma.tkpay.naps.NapsPayClient.printDuplicate] by [stan].
 * @property merchantReceipt Receipt from the Cancellation Response (TM=103), if any
 */
data class CancellationResult(
    val success: Boolean,
    val responseCode: String,
    val stan: String? = null,
    val error: String? = null,
    val pending: Boolean = false,
    val merchantReceipt: Receipt? = null
)
