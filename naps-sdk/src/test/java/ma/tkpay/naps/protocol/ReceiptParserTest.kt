package ma.tkpay.naps.protocol

import ma.tkpay.naps.models.Alignment
import ma.tkpay.naps.models.ReceiptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DP (010) print block, spec §5: line groups separated by '*', the last ending with '?'.
 */
class ReceiptParserTest {

    private fun line(n: String, format: String, align: String, text: String) =
        "030002$n" + "031001$format" + "032001$align" + "033" + text.length.toString().padStart(3, '0') + text

    // Example from spec §5
    private val specDp =
        line("01", "S", "G", "CENTRE MONETIQUE INTERBANCAIRE") + "*" +
        line("02", "S", "G", "09/05/11 08:12:55") + "*" +
        line("03", "G", "C", "Montant: 25040.00 MAD") + "*" +
        line("04", "S", "G", "TICKET A CONSERVER") + "?"

    @Test
    fun parsesAllStarSeparatedLines() {
        val receipt = ReceiptParser.parse(specDp, ReceiptType.CUSTOMER)
        val texts = receipt.lines.map { it.text }
        assertTrue(texts.contains("09/05/11 08:12:55"))
        assertTrue(texts.contains("Montant: 25040.00 MAD"))
        assertTrue(texts.contains("TICKET A CONSERVER"))
        val amount = receipt.lines.first { it.text.startsWith("Montant") }
        assertTrue(amount.bold)
        assertEquals(Alignment.CENTER, amount.alignment)
    }

    @Test
    fun dpWithEndMarkerSurvivesFrameParse() {
        // The '?' ending the print lines sits inside the DP value; fields after DP must survive
        val frame = TlvProtocol.buildField(TlvProtocol.Tags.TM, "102") +
            TlvProtocol.buildField(TlvProtocol.Tags.DP, specDp) +
            TlvProtocol.buildField(TlvProtocol.Tags.CR, "000") + "!"
        val fields = TlvProtocol.parse(frame)
        assertEquals(specDp, fields[TlvProtocol.Tags.DP])
        assertEquals("000", fields[TlvProtocol.Tags.CR])
    }

    // --- spec §5: never drop, merge, blank or invent lines ---

    @Test
    fun keepsLinesWithEmptyTextInOrder() {
        val dp = line("01", "S", "G", "A") + "*" + line("02", "S", "G", "") + "*" + line("03", "S", "G", "B") + "?"
        val receipt = ReceiptParser.parse(dp, ReceiptType.MERCHANT)
        assertEquals(listOf("01", "02", "03"), receipt.lines.map { it.lineNumber })
        assertEquals(listOf("A", "", "B"), receipt.lines.map { it.text })
    }

    @Test
    fun addsNoTextOfItsOwn() {
        val dp = line("01", "G", "C", "NAPS PAY") + "*" + line("02", "S", "G", "Copie Client") + "?"
        val receipt = ReceiptParser.parse(dp, ReceiptType.CUSTOMER)
        assertEquals(listOf("NAPS PAY", "Copie Client"), receipt.lines.map { it.text })
    }

    @Test
    fun masksFullPanOfAnyCardLengthWithoutBlankingTheLine() {
        val dp = line("01", "S", "G", "CARTE 4111111111111111") + "*" +
            line("02", "S", "G", "5167940123453") + "*" +              // 13 digits
            line("03", "S", "G", "6011000990139424127") + "*" +        // 19 digits
            line("04", "S", "G", "MID 000000000001") + "?"             // 12 digits: not a PAN
        val texts = ReceiptParser.parse(dp, ReceiptType.MERCHANT).lines.map { it.text }
        assertEquals(
            listOf("CARTE 411111******1111", "516794***3453", "601100*********4127", "MID 000000000001"),
            texts
        )
    }
}
