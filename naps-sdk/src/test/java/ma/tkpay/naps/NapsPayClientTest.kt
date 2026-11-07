package ma.tkpay.naps

import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.ReceiptType
import ma.tkpay.naps.protocol.TlvProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger

class NapsPayClientTest {

    private val client = NapsPayClient(NapsConfig(host = "127.0.0.1"))

    private fun receipt(text: String) =
        TlvProtocol.buildField(TlvProtocol.ReceiptTags.LINE_NUMBER, "01") +
        TlvProtocol.buildField(TlvProtocol.ReceiptTags.FORMAT, "S") +
        TlvProtocol.buildField(TlvProtocol.ReceiptTags.ALIGNMENT, "C") +
        TlvProtocol.buildField(TlvProtocol.ReceiptTags.CONTENT, text)

    @Test
    fun generateSequence_wrapsFrom999999To000001() {
        val field = NapsPayClient::class.java.getDeclaredField("sequenceGenerator")
        field.isAccessible = true
        (field.get(client) as AtomicInteger).set(999_998)

        assertEquals("999998", client.generateSequence())
        assertEquals("999999", client.generateSequence())
        assertEquals("000001", client.generateSequence())
        assertEquals("000002", client.generateSequence())
    }

    @Test
    fun successfulResult_takesMerchantReceiptFromPhase1AndCustomerFromPhase2() {
        val phase1 = mapOf(
            TlvProtocol.Tags.CR to "000",
            TlvProtocol.Tags.DP to receipt("COPIE COMMERCANT")
        )
        val phase2 = mapOf(
            TlvProtocol.Tags.CR to "000",
            TlvProtocol.Tags.STAN to "000123",
            TlvProtocol.Tags.NCAR to "516794******3315",
            TlvProtocol.Tags.DP to receipt("COPIE CLIENT")
        )

        val result = client.buildSuccessfulResult(phase1, phase2)

        assertTrue(result.isApproved())
        assertEquals("000123", result.stan)
        assertEquals(ReceiptType.MERCHANT, result.merchantReceipt?.type)
        assertTrue(result.merchantReceipt!!.toPlainText().contains("COPIE COMMERCANT"))
        assertEquals(ReceiptType.CUSTOMER, result.customerReceipt?.type)
        assertTrue(result.customerReceipt!!.toPlainText().contains("COPIE CLIENT"))
    }

    @Test
    fun failedResult_keepsMaskedPanAndDeclineReceipt() {
        val fields = mapOf(
            TlvProtocol.Tags.CR to "116",
            TlvProtocol.Tags.STAN to "000124",
            TlvProtocol.Tags.NCAR to "516794******3315",
            TlvProtocol.Tags.DP to receipt("REFUSE")
        )

        val result = client.buildFailedResult("116", fields)

        assertFalse(result.success)
        assertEquals("000124", result.stan)
        assertEquals("516794******3315", result.maskedCardNumber)
        assertNotNull(result.merchantReceipt)
        assertEquals("Insufficient funds — code: 116", result.error)
    }

    @Test
    fun failedResult_mapsSpecialCodes() {
        assertEquals("Terminal or server is down", client.buildFailedResult("909", emptyMap()).error)
        assertEquals("Transaction not found", client.buildFailedResult("302", emptyMap()).error)
        assertEquals("Transaction already cancelled", client.buildFailedResult("482", emptyMap()).error)
        assertEquals("Payment declined — code: 999", client.buildFailedResult("999", emptyMap()).error)
    }

    @Test
    fun paymentResult_phase2RefusalIsAFailedPayment() {
        val phase1 = mapOf(TlvProtocol.Tags.CR to "000", TlvProtocol.Tags.STAN to "000123")
        val phase2 = mapOf(TlvProtocol.Tags.CR to "302", TlvProtocol.Tags.STAN to "000123")

        val result = client.buildPaymentResult(phase1, phase2)

        assertFalse(result.success)
        assertEquals("302", result.responseCode)
        assertEquals("Transaction not found", result.error)
    }

    @Test
    fun paymentResult_phase2ApprovalIsApproved() {
        val phase1 = mapOf(TlvProtocol.Tags.CR to "000")
        val phase2 = mapOf(TlvProtocol.Tags.CR to "000", TlvProtocol.Tags.STAN to "000123")
        assertTrue(client.buildPaymentResult(phase1, phase2).isApproved())
    }

    // --- Cancellation confirmation (TM=104) classification, spec §4.4 ---

    private val cancelResponse by lazy {
        mapOf(
            TlvProtocol.Tags.TM to "103",
            TlvProtocol.Tags.CR to "000",
            TlvProtocol.Tags.STAN to "000123",
            TlvProtocol.Tags.MT to "000000001999",
            TlvProtocol.Tags.DP to receipt("ANNULATION")
        )
    }

    private fun confirm(cr: String) =
        mapOf(TlvProtocol.Tags.TM to "104", TlvProtocol.Tags.CR to cr, TlvProtocol.Tags.STAN to "000456")

    @Test
    fun cancellation_keepsMerchantCopyFromTm103AndCustomerCopyFromTm104() {
        val tm104 = confirm("000") + (TlvProtocol.Tags.DP to receipt("COPIE CLIENT"))
        val result = client.buildCancellationResult("000123", cancelResponse, tm104)
        assertTrue(result.merchantReceipt!!.toPlainText().contains("ANNULATION"))
        assertEquals(ReceiptType.MERCHANT, result.merchantReceipt?.type)
        assertTrue(result.customerReceipt!!.toPlainText().contains("COPIE CLIENT"))
        assertEquals(ReceiptType.CUSTOMER, result.customerReceipt?.type)
    }

    @Test
    fun cancellation_cr000And480AreSuccess() {
        for (cr in listOf("000", "480")) {
            val result = client.buildCancellationResult("000123", cancelResponse, confirm(cr))
            assertTrue(cr, result.success)
            assertFalse(cr, result.pending)
            assertEquals(cr, result.responseCode)
            assertEquals("000456", result.stan)
        }
    }

    @Test
    fun cancellation_noTm104ReplyIsPendingNotSuccess() {
        val result = client.buildCancellationResult("000123", cancelResponse, null)
        assertFalse(result.success)
        assertTrue(result.pending)
        assertEquals("000123", result.stan)
        assertTrue(result.merchantReceipt!!.toPlainText().contains("ANNULATION"))
    }

    @Test
    fun cancellation_cr995IsPendingNotSuccess() {
        val result = client.buildCancellationResult("000123", cancelResponse, confirm("995"))
        assertFalse(result.success)
        assertTrue(result.pending)
        assertEquals("995", result.responseCode)
        assertEquals("000123", result.stan)
        assertNotNull(result.merchantReceipt)
    }

    @Test
    fun cancellation_otherCodesAreHardFailures() {
        for (cr in listOf("482", "909", "992", "")) {
            val result = client.buildCancellationResult("000123", cancelResponse, confirm(cr))
            assertFalse(cr, result.success)
            assertFalse(cr, result.pending)
        }
    }

    @Test
    fun cancelPayment_terminalSilentAfterTm004FailsClosedAsPending() = runBlocking {
        val server = ServerSocket(0)
        val received = mutableListOf<String>()
        val terminal = thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val buf = ByteArray(1024)
                received += String(buf, 0, input.read(buf))  // TM=003
                val reply = TlvProtocol.buildField(TlvProtocol.Tags.TM, "103") +
                    TlvProtocol.buildField(TlvProtocol.Tags.CR, "000") +
                    TlvProtocol.buildField(TlvProtocol.Tags.STAN, "000123") +
                    TlvProtocol.buildField(TlvProtocol.Tags.MT, "000000001999") +
                    TlvProtocol.buildField(TlvProtocol.Tags.DP, receipt("ANNULATION")) + "!"
                socket.getOutputStream().write(reply.toByteArray())
                received += String(buf, 0, input.read(buf))  // TM=004, never answered
                Thread.sleep(2_500)
            }
        }
        val silentClient = NapsPayClient(
            NapsConfig(host = "127.0.0.1", port = server.localPort, confirmationTimeout = 1_500)
        )

        val result = silentClient.cancelPayment("000123", "01", "00001", "000045", 1999L)
        terminal.join()
        server.close()

        assertFalse(result.success)
        assertTrue(result.pending)
        assertEquals("000123", result.stan)
        assertNotNull(result.merchantReceipt)
        val tm004 = TlvProtocol.parse(received[1])
        assertEquals("004", tm004[TlvProtocol.Tags.TM])
        assertEquals("000045", tm004[TlvProtocol.Tags.NS])
        assertEquals("000000001999", tm004[TlvProtocol.Tags.MT])
    }
}
