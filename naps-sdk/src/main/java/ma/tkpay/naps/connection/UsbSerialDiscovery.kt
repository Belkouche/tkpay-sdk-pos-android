package ma.tkpay.naps.connection

/**
 * A USB device candidate for serial discovery — the minimal, Android-independent
 * shape of what [UsbSerialConnection] enumerates from the platform `UsbManager`.
 *
 * @property vendorId USB vendor id (e.g. 0x0B00 = 2816 for Ingenico).
 * @property productId USB product id.
 * @property deviceName platform device name/path (e.g. `/dev/bus/usb/001/002`).
 * @property isCdcAcm true when the device exposes a CDC-ACM (communications) class,
 *   the generic USB-serial fallback.
 */
data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val isCdcAcm: Boolean = false
)

/**
 * Pure selection logic for picking the Ingenico Lane/3000 out of a USB device list.
 *
 * This object holds **no Android dependencies** so it can be unit-tested on the JVM.
 * The Android-facing enumeration lives in [UsbSerialConnection].
 *
 * Selection order (mirrors the C# `PortDiscovery`):
 *   1. First device whose [UsbDeviceInfo.vendorId] equals [INGENICO_VENDOR_ID].
 *   2. Otherwise, the first CDC-ACM device (generic USB-serial fallback).
 *   3. Otherwise, `null`.
 */
object UsbSerialDiscovery {

    /** Ingenico USB vendor id: 0x0B00 (2816). */
    const val INGENICO_VENDOR_ID: Int = 0x0B00

    /**
     * Select the best terminal candidate from [devices], or null if none match.
     */
    fun select(devices: List<UsbDeviceInfo>): UsbDeviceInfo? =
        devices.firstOrNull { it.vendorId == INGENICO_VENDOR_ID }
            ?: devices.firstOrNull { it.isCdcAcm }

    /**
     * Find the terminal's device name from [devices], or null if none match.
     */
    fun findDeviceName(devices: List<UsbDeviceInfo>): String? = select(devices)?.deviceName

    /** True when [device] is the Ingenico terminal by vendor id. */
    fun isIngenico(device: UsbDeviceInfo): Boolean = device.vendorId == INGENICO_VENDOR_ID
}
