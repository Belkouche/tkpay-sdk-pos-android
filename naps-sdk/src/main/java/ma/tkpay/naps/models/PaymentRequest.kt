package ma.tkpay.naps.models

/**
 * Payment request data
 *
 * @property amount Transaction amount in MAD (Moroccan Dirham)
 * @property registerId Register/POS ID (2 digits, e.g., "01")
 * @property cashierId Cashier ID (5 digits, e.g., "00001")
 * @property sequence Optional sequence number (6 digits, auto-generated if not provided)
 */
data class PaymentRequest(
    val amount: Double,
    val registerId: String,
    val cashierId: String,
    val sequence: String? = null
) {
    init {
        require(amount > 0) { "Amount must be positive" }
        require(registerId.length == 2) { "Register ID must be 2 digits" }
        require(cashierId.length == 5) { "Cashier ID must be 5 digits" }
        sequence?.let {
            require(it.length == 6) { "Sequence must be 6 digits" }
        }
    }

    /**
     * Get NCAI (Register + Cashier combined identifier)
     * Format: RR + CCCCC = 7 characters
     */
    fun getNcai(): String = registerId + cashierId
}
