package ma.tkpay.naps.config

/**
 * NAPS TLV protocol version. Selects the expected end-of-message terminator:
 * v2 frames end with '!', v1 frames end with '?'.
 */
enum class ProtocolVersion(val terminator: Char) {
    V1('?'),
    V2('!')
}

/**
 * Terminal transport type.
 *
 * - [TCP_SOCKET] — Sunmi P2/P3 over a TCP/M2M socket (port 4444). Default.
 * - [USB_SERIAL] — Ingenico Lane/3000 over USB serial (CDC-ACM) via USB-host mode.
 *
 * The USB serial path requires the host device to support **USB host mode** and
 * a granted **runtime USB permission**; it cannot run against the TCP emulator.
 */
enum class TransportType {
    TCP_SOCKET,
    USB_SERIAL
}

/**
 * Configuration for a NAPS Pay terminal connection.
 *
 * Supports two transports through one config:
 * - **TCP/M2M socket** (Sunmi, default) — set [host]/[port].
 * - **USB serial** (Ingenico Lane/3000) — set [transport] = [TransportType.USB_SERIAL];
 *   [serialPortName] is optional (null = auto-discover by USB vendor id).
 *
 * @property host IP address of the terminal (required for TCP). Null/blank is only
 *   valid for USB serial.
 * @property port TCP port for M2M communication (default: 4444).
 * @property timeout Connection timeout in milliseconds (default: 120000ms = 2 minutes).
 * @property confirmationTimeout Timeout for payment confirmation (default: 40000ms = 40 seconds).
 * @property transport Terminal transport (default: [TransportType.TCP_SOCKET]).
 * @property serialPortName USB serial device name/path. Null (default) = auto-discover
 *   the Ingenico device by vendor id [UsbSerialDiscovery.INGENICO_VENDOR_ID].
 * @property serialBaudRate USB serial baud rate (default: 115200).
 * @property protocolVersion NAPS TLV protocol version. Null (default) resolves per
 *   transport via [resolveProtocolVersion]: TCP → [ProtocolVersion.V2] ('!'),
 *   USB serial → [ProtocolVersion.V1] ('?'). Set explicitly to override. The read
 *   loop tolerates both terminators regardless; this only chooses the primary one.
 */
data class NapsConfig(
    val host: String? = null,
    val port: Int = DEFAULT_PORT,
    val timeout: Long = DEFAULT_TIMEOUT,
    val confirmationTimeout: Long = CONFIRMATION_TIMEOUT,
    val transport: TransportType = TransportType.TCP_SOCKET,
    val serialPortName: String? = null,
    val serialBaudRate: Int = DEFAULT_BAUD_RATE,
    val protocolVersion: ProtocolVersion? = null
) {
    companion object {
        const val DEFAULT_PORT = 4444
        const val DEFAULT_TIMEOUT = 120000L  // 2 minutes
        const val CONFIRMATION_TIMEOUT = 40000L  // 40 seconds
        const val DEFAULT_BAUD_RATE = 115200

        /**
         * Create config for localhost (for testing on same device)
         */
        fun localhost(): NapsConfig = NapsConfig(
            host = "127.0.0.1",
            port = DEFAULT_PORT
        )

        /**
         * Create config for an Ingenico Lane/3000 over USB serial.
         *
         * @param serialPortName device name/path, or null to auto-discover by vendor id.
         */
        fun usbSerial(serialPortName: String? = null): NapsConfig = NapsConfig(
            transport = TransportType.USB_SERIAL,
            serialPortName = serialPortName
        )
    }

    /**
     * Effective protocol version: the explicit [protocolVersion] if set, otherwise
     * the per-transport default — USB serial → [ProtocolVersion.V1], TCP →
     * [ProtocolVersion.V2].
     */
    fun resolveProtocolVersion(): ProtocolVersion =
        protocolVersion ?: when (transport) {
            TransportType.USB_SERIAL -> ProtocolVersion.V1
            TransportType.TCP_SOCKET -> ProtocolVersion.V2
        }

    init {
        if (transport == TransportType.TCP_SOCKET) {
            require(!host.isNullOrBlank()) { "Host cannot be blank for TCP transport" }
        }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(timeout > 0) { "Timeout must be positive" }
        require(confirmationTimeout > 0) { "Confirmation timeout must be positive" }
        require(serialBaudRate > 0) { "Serial baud rate must be positive" }
    }
}
