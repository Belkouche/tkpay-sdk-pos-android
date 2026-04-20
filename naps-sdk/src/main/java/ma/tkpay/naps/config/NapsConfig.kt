package ma.tkpay.naps.config

/**
 * Configuration for NAPS Pay M2M connection
 *
 * @property host IP address of NAPS Pay terminal
 * @property port TCP port for M2M communication (default: 4444)
 * @property timeout Connection timeout in milliseconds (default: 120000ms = 2 minutes)
 * @property confirmationTimeout Timeout for payment confirmation (default: 40000ms = 40 seconds)
 */
data class NapsConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val timeout: Long = DEFAULT_TIMEOUT,
    val confirmationTimeout: Long = CONFIRMATION_TIMEOUT
) {
    companion object {
        const val DEFAULT_PORT = 4444
        const val DEFAULT_TIMEOUT = 120000L  // 2 minutes
        const val CONFIRMATION_TIMEOUT = 40000L  // 40 seconds

        /**
         * Create config for localhost (for testing on same device)
         */
        fun localhost(): NapsConfig = NapsConfig(
            host = "127.0.0.1",
            port = DEFAULT_PORT
        )
    }

    init {
        require(host.isNotBlank()) { "Host cannot be blank" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(timeout > 0) { "Timeout must be positive" }
        require(confirmationTimeout > 0) { "Confirmation timeout must be positive" }
    }
}
