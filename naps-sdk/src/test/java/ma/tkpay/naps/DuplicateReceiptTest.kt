package ma.tkpay.naps

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.protocol.TlvProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class DuplicateReceiptTest {

    /** One-shot terminal: reads the request, answers [reply]. */
    private fun duplicate(reply: String) = ServerSocket(0).use { server ->
        val t = thread {
            server.accept().use { s ->
                s.getInputStream().read(ByteArray(4096))
                s.getOutputStream().write(reply.toByteArray())
                Thread.sleep(300)
            }
        }
        val dup = runBlocking { NapsPayClient(NapsConfig(host = "127.0.0.1", port = server.localPort)).printDuplicate("01", "00001") }
        t.join()
        dup
    }

    private fun tlv(tag: String, value: String) = TlvProtocol.buildField(tag, value)

    @Test
    fun foundTransactionExposesItsStan() {
        val dup = duplicate(tlv("001", "108") + tlv("008", "220647") + tlv("013", "000") + "!")
        assertTrue(dup.success)
        assertEquals("220647", dup.stan)
    }

    @Test
    fun notFoundHasNoStan() {
        val dup = duplicate(tlv("001", "108") + tlv("013", "302") + "!")
        assertFalse(dup.success)
        assertEquals("302", dup.responseCode)
        assertNull(dup.stan)
    }
}
