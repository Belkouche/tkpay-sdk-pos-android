package ma.tkpay.naps.protocol

import ma.tkpay.naps.models.Alignment
import ma.tkpay.naps.models.ReceiptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden receipts: DP blocks captured from tkpay-naps-emulator (10.00 MAD payment
 * and its void) in src/test/resources/receipts. Every line must come out in order,
 * unchanged, with nothing added or dropped.
 */
class ReceiptGoldenTest {

    private fun dp(name: String): String =
        javaClass.getResourceAsStream("/receipts/$name.dp")!!.bufferedReader().readText()

    private fun golden(stan: String, auth: String, date: String, operation: String, copy: String) = listOf(
        "TKpay",
        "--------------------------------",
        date,
        "TKPAY DEMO",
        "CASABLANCA",
        "--------------------------------",
        "VISA",
        "516794******3315",
        "Terminal: 00000001",
        "Transaction: $stan",
        "Autorisation: $auth",
        "STAN: $stan",
        "--------------------------------",
        "MONTANT: 10.00 MAD",
        "--------------------------------",
        operation,
        copy
    )

    private fun rendered(name: String, type: ReceiptType) =
        ReceiptParser.parse(dp(name), type).toPlainText().lines().map { it.trim() }

    @Test
    fun paymentMerchantCopy() {
        assertEquals(
            golden("594963", "378366", "07/11/2026 00:13:46", "DEBIT", "Copie Commercant"),
            rendered("payment-merchant", ReceiptType.MERCHANT)
        )
    }

    @Test
    fun paymentCustomerCopy() {
        assertEquals(
            golden("594963", "695896", "07/11/2026 00:13:46", "DEBIT", "Copie Client"),
            rendered("payment-customer", ReceiptType.CUSTOMER)
        )
    }

    @Test
    fun voidMerchantCopy() {
        assertEquals(
            golden("594963", "989115", "07/11/2026 00:13:46", "ANNULATION", "Copie Commercant"),
            rendered("void-merchant", ReceiptType.MERCHANT)
        )
    }

    @Test
    fun voidCustomerCopy() {
        assertEquals(
            golden("544256", "718799", "07/11/2026 00:13:47", "ANNULATION", "Copie Client"),
            rendered("void-customer", ReceiptType.CUSTOMER)
        )
    }

    @Test
    fun keepsLineNumberBoldAndAlignmentPerLine() {
        val lines = ReceiptParser.parse(dp("payment-customer"), ReceiptType.CUSTOMER).lines
        assertEquals("00", lines[0].lineNumber)
        assertTrue(lines[0].bold)
        assertEquals(Alignment.CENTER, lines[0].alignment)
        assertEquals("24", lines.last().lineNumber)
        assertFalse(lines.last().bold)
        assertEquals(Alignment.LEFT, lines.last().alignment)
    }
}
