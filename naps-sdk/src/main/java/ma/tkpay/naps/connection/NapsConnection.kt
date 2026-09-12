package ma.tkpay.naps.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.NapsError
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP connection manager for NAPS Pay terminal
 *
 * Handles:
 * - Socket connection lifecycle
 * - Send/receive TLV messages
 * - Timeout management
 * - Connection reuse for two-phase payment flow
 */
class NapsConnection(private val config: NapsConfig) : NapsTransport {

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    /**
     * Check if connection is open and valid
     */
    override val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Connect to NAPS Pay terminal
     */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        val host = config.host
            ?: throw NapsError.connectionFailed(IllegalStateException("TCP host not set"))
        try {
            disconnect()  // Close any existing connection

            socket = Socket().apply {
                soTimeout = config.timeout.toInt()
                connect(InetSocketAddress(host, config.port), config.timeout.toInt())
            }

            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

        } catch (e: Exception) {
            disconnect()
            throw NapsError.connectionFailed(e)
        }
    }

    /**
     * Send TLV message and receive response
     *
     * @param tlvMessage TLV formatted message to send
     * @param timeout Timeout in milliseconds (optional, uses config default)
     * @return TLV response string
     */
    override suspend fun sendAndReceive(
        tlvMessage: String,
        timeout: Long
    ): String = withContext(Dispatchers.IO) {
        if (!isConnected) {
            throw NapsError.connectionFailed(IllegalStateException("Not connected"))
        }

        try {
            // Send message
            writer!!.print(tlvMessage)
            writer!!.flush()

            // Receive response with timeout
            withTimeout(timeout) {
                receiveMessage()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw NapsError.timeout(e)
        } catch (e: Exception) {
            throw NapsError.connectionFailed(e)
        }
    }

    /**
     * Receive message from socket
     *
     * Stops as soon as a NAPS end-of-message terminator is seen ('!' for v2,
     * '?' for v1); otherwise reads until no more data is available for 1 second
     * (NAPS Pay terminal keeps connection open after sending response).
     */
    private suspend fun receiveMessage(): String = withContext(Dispatchers.IO) {
        val response = StringBuilder()
        val buffer = CharArray(8192)

        // Set short timeout for detecting end of message
        val originalTimeout = socket?.soTimeout ?: 1000
        socket?.soTimeout = 1000

        try {
            while (true) {
                try {
                    val count = reader!!.read(buffer)
                    if (count == -1) break  // Connection closed
                    if (count == 0) break   // No data

                    response.append(buffer, 0, count)

                    // Stop immediately once the frame terminator arrives.
                    if (endsFrame(response)) break

                    // If less than buffer size, we likely have all data
                    if (count < buffer.size) {
                        // Try to read more, but timeout quickly if nothing
                        try {
                            val additionalCount = reader!!.read(buffer)
                            if (additionalCount > 0) {
                                response.append(buffer, 0, additionalCount)
                            }
                        } catch (e: Exception) {
                            // Timeout means we have all the data
                            break
                        }
                        break
                    }
                } catch (e: Exception) {
                    // Timeout means we have all the data
                    if (response.isNotEmpty()) break
                    throw e
                }
            }
        } finally {
            // Restore original timeout
            socket?.soTimeout = originalTimeout
        }

        if (response.isEmpty()) {
            throw NapsError.invalidResponse("Empty response from terminal")
        }

        response.toString()
    }

    /**
     * True once the accumulated response carries a NAPS end-of-message
     * terminator: '!' (v2) or '?' (v1). Both are recognised regardless of the
     * configured [NapsConfig.protocolVersion] so a terminal that answers with
     * the other version's terminator is still handled.
     */
    private fun endsFrame(response: StringBuilder): Boolean {
        for (i in response.indices) {
            val c = response[i]
            if (c == '!' || c == '?') return true
        }
        return false
    }

    /**
     * Disconnect from terminal
     */
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            // Ignore errors during disconnect
        } finally {
            writer = null
            reader = null
            socket = null
        }
        Unit
    }
}
