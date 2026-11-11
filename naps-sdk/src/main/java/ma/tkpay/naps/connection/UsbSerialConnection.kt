package ma.tkpay.naps.connection

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.protocol.TlvProtocol
import kotlin.coroutines.resume

/**
 * USB serial transport for the Ingenico Lane/3000 (CDC-ACM) over Android **USB-host mode**.
 *
 * Uses the platform [UsbManager] plus the `usb-serial-for-android` library. Because
 * USB access requires an explicit runtime grant, [connect] first requests USB
 * permission via a broadcast flow if the app doesn't already hold it.
 *
 * Each request ends with the NAPS terminator (`'!'` v2 / `'?'` v1) and is written only when
 * [SerialTiming] allows it; the response is read until a terminator, with a short
 * inter-chunk silence fallback. After a timeout or error the port is closed, so a reply that
 * arrives late is never read as the answer to the next request.
 *
 * NOTE: This path cannot run against the TCP emulator and requires USB-host hardware
 * plus a granted USB permission. It is unit-tested at the logic layer
 * ([UsbSerialDiscovery]) only — the on-device serial I/O here is not emulator-verified.
 */
class UsbSerialConnection(
    private val context: Context,
    private val config: NapsConfig
) : NapsTransport {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    private var openedAt = 0L
    private var lastRxAt = 0L

    override val isConnected: Boolean
        get() = port?.isOpen == true

    /** Snapshot of the currently attached USB devices as plain [UsbDeviceInfo]. */
    fun listDevices(): List<UsbDeviceInfo> =
        usbManager.deviceList.values.map { it.toInfo() }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()

        val device = resolveDevice()
            ?: throw NapsError.connectionFailed(
                IllegalStateException("Ingenico Lane/3000 not found. Check USB connection.")
            )

        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            if (!usbManager.hasPermission(device)) {
                throw NapsError.connectionFailed(
                    IllegalStateException("USB permission denied for ${device.deviceName}")
                )
            }
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw NapsError.connectionFailed(
                IllegalStateException("No USB serial driver for ${device.deviceName}")
            )

        val connection = usbManager.openDevice(device)
            ?: throw NapsError.connectionFailed(
                IllegalStateException("Failed to open USB device ${device.deviceName}")
            )

        try {
            val p = driver.ports.first()
            p.open(connection)
            p.setParameters(
                config.serialBaudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            p.dtr = false
            p.rts = false
            port = p
            openedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            try { connection.close() } catch (_: Exception) { }
            throw NapsError.connectionFailed(e)
        }
    }

    override suspend fun sendAndReceive(
        tlvMessage: String,
        timeout: Long
    ): String = withContext(Dispatchers.IO) {
        val p = port ?: throw NapsError.connectionFailed(IllegalStateException("Not connected"))
        delay(SerialTiming.remainingWait(openedAt, lastRxAt, System.currentTimeMillis(), tlvMessage))
        val terminator = config.resolveProtocolVersion().terminator.toString()
        val frame = if (tlvMessage.endsWith(terminator)) tlvMessage else tlvMessage + terminator
        try {
            p.write(frame.toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
            withTimeout(timeout) { readResponse(p) }.also { lastRxAt = System.currentTimeMillis() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            disconnect()
            throw NapsError.timeout(e)
        } catch (e: Exception) {
            disconnect()
            throw NapsError.connectionFailed(e)
        }
    }

    /**
     * Read until a NAPS terminator (`'!'`/`'?'`) is seen, or until [SILENCE_MS] of
     * no new bytes once at least one byte has arrived.
     */
    private fun readResponse(p: UsbSerialPort): String {
        val out = StringBuilder()
        val buf = ByteArray(READ_BUFFER_SIZE)
        var lastByteAt = System.currentTimeMillis()

        while (true) {
            val n = try {
                p.read(buf, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                if (out.isNotEmpty()) break else throw e
            }

            if (n > 0) {
                out.append(String(buf, 0, n, Charsets.US_ASCII))
                lastByteAt = System.currentTimeMillis()
                if (out.any { it == '!' || it == '?' }) break
            } else {
                // No data this poll — bail once we've received something and gone quiet.
                if (out.isNotEmpty() && System.currentTimeMillis() - lastByteAt >= SILENCE_MS) break
            }
        }

        if (out.isEmpty()) throw NapsError.invalidResponse("Empty response from terminal")
        // SECURITY: card numbers are masked before the reply leaves the transport
        return TlvProtocol.maskFrame(out.toString())
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try { port?.close() } catch (_: Exception) { }
        port = null
    }

    /** Resolve the target device: explicit [NapsConfig.serialPortName] wins, else discovery. */
    private fun resolveDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values.toList()
        config.serialPortName?.let { name ->
            devices.firstOrNull { it.deviceName == name }?.let { return it }
        }
        val target = UsbSerialDiscovery.select(devices.map { it.toInfo() }) ?: return null
        return devices.firstOrNull { it.deviceName == target.deviceName }
    }

    private suspend fun requestPermission(device: UsbDevice) {
        suspendCancellableCoroutine<Unit> { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    try { context.unregisterReceiver(this) } catch (_: Exception) { }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "ma.tkpay.naps.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 200
        private const val READ_BUFFER_SIZE = 8192
        private const val SILENCE_MS = 1_000L

        /**
         * List all attached USB devices as plain [UsbDeviceInfo] (no permission needed).
         */
        fun listDevices(context: Context): List<UsbDeviceInfo> {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return usbManager.deviceList.values.map { it.toInfo() }
        }

        /**
         * Find the Ingenico device name via [UsbSerialDiscovery], or null if absent.
         */
        fun findDeviceName(context: Context): String? =
            UsbSerialDiscovery.findDeviceName(listDevices(context))
    }
}

private fun UsbDevice.toInfo(): UsbDeviceInfo =
    UsbDeviceInfo(
        vendorId = vendorId,
        productId = productId,
        deviceName = deviceName,
        isCdcAcm = (0 until interfaceCount).any {
            getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM
        }
    )
