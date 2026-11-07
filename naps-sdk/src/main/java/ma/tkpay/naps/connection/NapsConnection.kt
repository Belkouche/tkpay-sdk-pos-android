package ma.tkpay.naps.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.protocol.TlvProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

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

    companion object {
        /** Upper bound for one response frame (DP receipts are at most 3500 chars). */
        internal const val MAX_FRAME_CHARS = 65_536
    }

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
                receiveMessage(timeout)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw NapsError.timeout(e)
        } catch (e: SocketTimeoutException) {
            throw NapsError.timeout(e)
        } catch (e: NapsError) {
            throw e
        } catch (e: Exception) {
            throw NapsError.connectionFailed(e)
        }
    }

    /**
     * Receive message from socket
     *
     * The first read blocks for up to [timeout] ms (the terminal only answers once
     * the cardholder has acted). After that, chunks are drained until the frame
     * ends with a NAPS terminator ('!' for v2, '?' for v1) or until 1 second of
     * silence (NAPS Pay terminal keeps connection open after sending response).
     * The whole read is bounded by [timeout] and [MAX_FRAME_CHARS] so a peer that
     * trickles bytes without a terminator cannot hold the call open.
     */
    private suspend fun receiveMessage(timeout: Long): String = withContext(Dispatchers.IO) {
        val response = StringBuilder()
        val buffer = CharArray(8192)

        val originalTimeout = socket?.soTimeout ?: 0
        val deadline = System.nanoTime() + timeout * 1_000_000

        try {
            // First read — wait for the terminal for the whole exchange timeout
            socket?.soTimeout = timeout.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val first = reader!!.read(buffer)
            if (first == -1) throw NapsError.invalidResponse("Connection closed before response")
            response.append(buffer, 0, first)

            // Drain remaining chunks with a 1-second inter-chunk gap
            socket?.soTimeout = 1000
            while (!endsFrame(response)) {
                coroutineContext.ensureActive()
                if (System.nanoTime() > deadline) throw NapsError.timeout()
                if (response.length > MAX_FRAME_CHARS) {
                    throw NapsError.invalidResponse("Response exceeds $MAX_FRAME_CHARS chars without terminator")
                }
                val count = try {
                    reader!!.read(buffer)
                } catch (e: SocketTimeoutException) {
                    break  // 1-second silence — treat as end-of-message
                }
                if (count == -1) break  // Connection closed
                response.append(buffer, 0, count)
            }
        } finally {
            // Restore original timeout
            socket?.soTimeout = originalTimeout
        }

        if (response.isEmpty()) {
            throw NapsError.invalidResponse("Empty response from terminal")
        }

        // SECURITY: card numbers are masked before the reply leaves the transport
        TlvProtocol.maskFrame(response.toString())
    }

    /**
     * True once the accumulated response ends with a NAPS end-of-message
     * terminator: '!' (v2) or '?' (v1). Both are recognised regardless of the
     * configured [NapsConfig.protocolVersion] so a terminal that answers with
     * the other version's terminator is still handled.
     */
    private fun endsFrame(response: StringBuilder): Boolean {
        val last = response.lastOrNull() ?: return false
        return last == '!' || last == '?'
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
