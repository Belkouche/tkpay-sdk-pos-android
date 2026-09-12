package ma.tkpay.naps.connection

/**
 * Abstraction over the link to a NAPS Pay terminal.
 *
 * Two implementations exist:
 * - [NapsConnection] — TCP/M2M socket (Sunmi P2/P3), the default.
 * - [UsbSerialConnection] — USB serial CDC-ACM (Ingenico Lane/3000), USB-host mode.
 *
 * Both speak the same TLV request/response framing: the raw request is written
 * verbatim (no terminator) and the response is read until the NAPS end-of-message
 * terminator (`'!'` v2 / `'?'` v1) with a short inter-chunk silence fallback.
 */
interface NapsTransport {

    /** True when the underlying link is open and usable. */
    val isConnected: Boolean

    /** Open the link. Throws [ma.tkpay.naps.models.NapsError] on failure. */
    suspend fun connect()

    /**
     * Send a TLV message and read the response.
     *
     * @param tlvMessage raw TLV request to write verbatim.
     * @param timeout read timeout in milliseconds.
     * @return the raw TLV response string.
     */
    suspend fun sendAndReceive(tlvMessage: String, timeout: Long): String

    /** Close the link, swallowing errors. */
    suspend fun disconnect()
}

/**
 * Run [block] with the transport connected, always disconnecting afterwards.
 */
suspend fun <T> NapsTransport.use(block: suspend (NapsTransport) -> T): T {
    return try {
        connect()
        block(this)
    } finally {
        disconnect()
    }
}
