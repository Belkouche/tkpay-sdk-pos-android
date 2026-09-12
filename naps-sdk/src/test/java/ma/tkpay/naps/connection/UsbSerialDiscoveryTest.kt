package ma.tkpay.naps.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for USB serial device selection. No Android or hardware.
 */
class UsbSerialDiscoveryTest {

    private fun ingenico(name: String = "/dev/ingenico") =
        UsbDeviceInfo(vendorId = 0x0B00, productId = 0x0042, deviceName = name)

    private fun cdcAcm(name: String = "/dev/ttyACM0") =
        UsbDeviceInfo(vendorId = 0x1234, productId = 0x0001, deviceName = name, isCdcAcm = true)

    private fun other(name: String = "/dev/other") =
        UsbDeviceInfo(vendorId = 0x2222, productId = 0x0002, deviceName = name, isCdcAcm = false)

    @Test
    fun select_prefersIngenicoByVendorId() {
        val devices = listOf(other(), cdcAcm(), ingenico())
        val picked = UsbSerialDiscovery.select(devices)
        assertEquals(0x0B00, picked?.vendorId)
        assertEquals("/dev/ingenico", picked?.deviceName)
    }

    @Test
    fun select_ingenicoWinsOverCdcAcmFallback() {
        // A CDC-ACM device appears first, but Ingenico VID must still win.
        val devices = listOf(cdcAcm("/dev/ttyACM0"), ingenico("/dev/ingenico"))
        assertEquals("/dev/ingenico", UsbSerialDiscovery.select(devices)?.deviceName)
    }

    @Test
    fun select_fallsBackToCdcAcmWhenNoVendorMatch() {
        val devices = listOf(other(), cdcAcm("/dev/ttyACM0"))
        val picked = UsbSerialDiscovery.select(devices)
        assertEquals("/dev/ttyACM0", picked?.deviceName)
        assertTrue(picked!!.isCdcAcm)
    }

    @Test
    fun select_returnsNullWhenNothingMatches() {
        val devices = listOf(other("/dev/a"), other("/dev/b"))
        assertNull(UsbSerialDiscovery.select(devices))
    }

    @Test
    fun select_returnsNullForEmptyList() {
        assertNull(UsbSerialDiscovery.select(emptyList()))
    }

    @Test
    fun findDeviceName_returnsSelectedName() {
        val devices = listOf(other(), ingenico("/dev/bus/usb/001/004"))
        assertEquals("/dev/bus/usb/001/004", UsbSerialDiscovery.findDeviceName(devices))
    }

    @Test
    fun findDeviceName_nullWhenNoneMatch() {
        assertNull(UsbSerialDiscovery.findDeviceName(listOf(other())))
    }

    @Test
    fun isIngenico_matchesVendorId() {
        assertTrue(UsbSerialDiscovery.isIngenico(ingenico()))
        assertFalse(UsbSerialDiscovery.isIngenico(cdcAcm()))
        assertFalse(UsbSerialDiscovery.isIngenico(other()))
    }

    @Test
    fun vendorIdConstant_is0x0B00() {
        assertEquals(2816, UsbSerialDiscovery.INGENICO_VENDOR_ID)
        assertEquals(0x0B00, UsbSerialDiscovery.INGENICO_VENDOR_ID)
    }
}
