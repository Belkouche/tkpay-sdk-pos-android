package ma.tkpay.naps.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for per-transport protocol defaults and config validation.
 */
class NapsConfigTest {

    @Test
    fun tcpTransport_defaultsToV2() {
        val config = NapsConfig(host = "192.168.1.10")
        assertEquals(ProtocolVersion.V2, config.resolveProtocolVersion())
    }

    @Test
    fun usbSerialTransport_defaultsToV2() {
        // The Lane3600 over USB ends every reply with '!'.
        val config = NapsConfig(transport = TransportType.USB_SERIAL)
        assertEquals(ProtocolVersion.V2, config.resolveProtocolVersion())
    }

    @Test
    fun explicitProtocolVersion_overridesTcpDefault() {
        val config = NapsConfig(host = "192.168.1.10", protocolVersion = ProtocolVersion.V1)
        assertEquals(ProtocolVersion.V1, config.resolveProtocolVersion())
    }

    @Test
    fun explicitProtocolVersion_overridesSerialDefault() {
        val config = NapsConfig(
            transport = TransportType.USB_SERIAL,
            protocolVersion = ProtocolVersion.V2
        )
        assertEquals(ProtocolVersion.V2, config.resolveProtocolVersion())
    }

    @Test
    fun usbSerialFactory_buildsSerialTransportWithoutHost() {
        val config = NapsConfig.usbSerial()
        assertEquals(TransportType.USB_SERIAL, config.transport)
        assertEquals(ProtocolVersion.V2, config.resolveProtocolVersion())
        assertEquals(NapsConfig.DEFAULT_BAUD_RATE, config.serialBaudRate)
    }

    @Test
    fun usbSerialFactory_carriesExplicitPortName() {
        val config = NapsConfig.usbSerial("/dev/ttyACM0")
        assertEquals("/dev/ttyACM0", config.serialPortName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tcpTransport_requiresHost() {
        NapsConfig(transport = TransportType.TCP_SOCKET, host = null)
    }

    @Test
    fun terminatorChars_matchProtocolVersion() {
        assertEquals('!', ProtocolVersion.V2.terminator)
        assertEquals('?', ProtocolVersion.V1.terminator)
    }
}
