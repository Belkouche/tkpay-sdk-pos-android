package ma.tkpay.naps

import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.ReceiptType
import ma.tkpay.naps.protocol.TlvProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
