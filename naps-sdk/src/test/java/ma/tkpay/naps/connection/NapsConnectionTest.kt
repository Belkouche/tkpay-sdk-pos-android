package ma.tkpay.naps.connection

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.ErrorCode
import ma.tkpay.naps.models.NapsError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Socket-level tests for the TCP read loop against a local fake terminal.
 */
class NapsConnectionTest {

    private val server = ServerSocket(0)

    @After
    fun tearDown() {
        server.close()
    }

    /** Accept one client, read its request, then write [chunks] with [delaysMs] before each. */
    private fun fakeTerminal(chunks: List<String>, delaysMs: List<Long>) = thread {
        server.accept().use { client ->
            client.getInputStream().read(ByteArray(1024))
            val out = client.getOutputStream()
            chunks.forEachIndexed { i, chunk ->
                Thread.sleep(delaysMs[i])
                out.write(chunk.toByteArray())
                out.flush()
            }
            Thread.sleep(1500)
        }
    }

    private fun connection() = NapsConnection(NapsConfig(host = "127.0.0.1", port = server.localPort))

    @Test
    fun waitsLongerThanOneSecondForFirstByte() = runBlocking {
        val t = fakeTerminal(listOf("001003101013003000!"), listOf(1500))
        val conn = connection()
        conn.connect()
        val response = conn.sendAndReceive("001003009", timeout = 5_000)
        assertEquals("001003101013003000!", response)
        conn.disconnect()
        t.join()
    }

    @Test
    fun drainsMultiChunkFrameUntilTerminator() = runBlocking {
        val t = fakeTerminal(listOf("001003101", "013003000", "008006000123!"), listOf(0, 200, 200))
        val conn = connection()
        conn.connect()
        val response = conn.sendAndReceive("001003009", timeout = 5_000)
        assertEquals("001003101013003000008006000123!", response)
        conn.disconnect()
        t.join()
    }

    @Test
    fun throwsTimeoutWhenTerminalSilent() = runBlocking {
        val t = fakeTerminal(listOf("001003101!"), listOf(2_500))
        val conn = connection()
        conn.connect()
        try {
            conn.sendAndReceive("001003009", timeout = 1_000)
            fail("expected timeout")
        } catch (e: NapsError) {
            assertEquals(ErrorCode.TIMEOUT, e.code)
        }
        conn.disconnect()
        t.join()
    }

    @Test
    fun tricklingPeerWithoutTerminatorHitsTotalDeadline() = runBlocking {
        // One byte every 300 ms, never a terminator: the 1 s silence fallback never fires
        val t = fakeTerminal(List(20) { "0" }, List(20) { 300L })
        val conn = connection()
        conn.connect()
        val start = System.currentTimeMillis()
        try {
            conn.sendAndReceive("001003009", timeout = 1_500)
            fail("expected timeout")
        } catch (e: NapsError) {
            assertEquals(ErrorCode.TIMEOUT, e.code)
        }
        assertTrue(System.currentTimeMillis() - start < 3_000)
        conn.disconnect()
        t.join()
    }
}
