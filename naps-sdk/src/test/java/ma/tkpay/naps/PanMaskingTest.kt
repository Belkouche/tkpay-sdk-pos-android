package ma.tkpay.naps

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.connection.NapsConnection
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.ReceiptType
import ma.tkpay.naps.protocol.ReceiptParser
import ma.tkpay.naps.protocol.TlvProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A full card number sent by the terminal must never reach a result, receipt line,
 * raw frame, request frame, error message or log output — and receipt lines must
 * otherwise be unchanged.
 */
class PanMaskingTest {

    private val pan = "5167940000123315"
    private val spacedPan = "5167 9400 0012 3315"
    private val pan19 = "6011000990139424127"
    private val forbidden = listOf(pan, spacedPan, pan19, "516794000012", "601100099013942")

    private fun printLine(n: String, text: String) =
        "030002$n" + "031001S" + "032001G" + "033" + text.length.toString().padStart(3, '0') + text

    private val dp = printLine("01", "TKpay") + "*" +
        printLine("02", "CARTE $spacedPan") + "*" +
        printLine("03", "REF $pan19 FIN") + "*" +
        printLine("04", "MONTANT: 10.00 MAD") + "*" +
        printLine("05", "Copie Client") + "?"

    private fun reply(tm: String, cr: String = "000", stan: String? = "000123") =
        TlvProtocol.buildField(TlvProtocol.Tags.TM, tm) +
        TlvProtocol.buildField(TlvProtocol.Tags.CR, cr) +
        (stan?.let { TlvProtocol.buildField(TlvProtocol.Tags.STAN, it) } ?: "") +
        TlvProtocol.buildField(TlvProtocol.Tags.NS, "000001") +
        TlvProtocol.buildField(TlvProtocol.Tags.NCAR, pan) +
        TlvProtocol.buildField(TlvProtocol.Tags.DAEX, "3010") +
        TlvProtocol.buildField(TlvProtocol.Tags.DP, dp) + "!"

    private fun assertNoPan(where: String, text: String?) {
        if (text == null) return
        for (f in forbidden) assertFalse("$where contains full card number: $text", text.contains(f))
    }

    // --- parsing ---

    @Test
    fun parse_masksNcarAndDpText() {
        val fields = TlvProtocol.parse(reply("101"))
        assertEquals("516794******3315", fields[TlvProtocol.Tags.NCAR])
        fields.forEach { (tag, value) -> assertNoPan("field $tag", value) }
    }

    @Test
    fun parse_ncarWithSeparatorsGivesStandardMaskedForm() {
        val fields = TlvProtocol.parse(TlvProtocol.buildField(TlvProtocol.Tags.NCAR, "5167-9400-0012-3315"))
        assertEquals("516794******3315", fields[TlvProtocol.Tags.NCAR])
    }

    @Test
    fun receipt_masksOnlyTheCardNumbersAndKeepsEveryLine() {
        val receipt = ReceiptParser.parse(dp, ReceiptType.CUSTOMER)
        assertEquals(
            listOf("TKpay", "CARTE 5167 94** **** 3315", "REF 601100*********4127 FIN", "MONTANT: 10.00 MAD", "Copie Client"),
            receipt.lines.map { it.text }
        )
        assertEquals(listOf("01", "02", "03", "04", "05"), receipt.lines.map { it.lineNumber })
    }

    @Test
    fun maskFrame_keepsLengthAndStructure() {
        val raw = reply("101")
        val masked = TlvProtocol.maskFrame(raw)
        assertEquals(raw.length, masked.length)
        assertNoPan("masked frame", masked)
        assertEquals(TlvProtocol.parse(raw), TlvProtocol.parse(masked))
        assertTrue(masked.endsWith("!"))
    }

    @Test
    fun maskCardNumbersInText_sharedTestVectors() {
        val vectors = listOf(
            "5167940000123315" to "516794******3315",
            "5167 9400 0012 3315" to "5167 94** **** 3315",
            "5167-9400-0012-3315" to "5167-94**-****-3315",
            "5167.9400.0012.3315" to "5167.94**.****.3315",
            "6011000990139424127" to "601100*********4127",
            "5167 9400 0012 3315 0000" to "5167 94** **** **** 0000",
            "5167\t9400\t0012\t3315" to "5167\t94**\t****\t3315",
            "5167\u00A09400\u00A00012\u00A03315" to "5167\u00A094**\u00A0****\u00A03315",
            "07/11/2026 00:12:10" to "07/11/2026 00:12:10",
            "MONTANT: 10.00 MAD" to "MONTANT: 10.00 MAD",
            "MONTANT: 1 250,00 MAD" to "MONTANT: 1 250,00 MAD",
            "Terminal: 00000001" to "Terminal: 00000001",
            "STAN: 489688 Autorisation: 963962" to "STAN: 489688 Autorisation: 963962",
            "516794000012" to "516794000012",                 // 12 digits: not card-shaped
            "5167  9400  0012  3315" to "5167  9400  0012  3315"  // double spaces end the run
        )
        for ((input, expected) in vectors) {
            val masked = TlvProtocol.maskCardNumbersInText(input)
            println("VECTOR ${input.replace("\t", "\\t").replace("\u00A0", "<NBSP>")} -> " +
                masked.replace("\t", "\\t").replace("\u00A0", "<NBSP>"))
            assertEquals(input, expected, masked)
            assertEquals(input.length, masked.length)
        }
    }

    @Test
    fun maskCardNumbersInText_leavesNonCardNumbersAlone() {
        for (text in listOf("07/11/2026 00:16:42", "MID: 000000000001", "STAN: 489688", "VENTES:     0000000",
                "Terminal: 00000001", "516794******3315")) {
            assertEquals(text, TlvProtocol.maskCardNumbersInText(text))
        }
        assertEquals("X516794******3315", TlvProtocol.maskCardNumbersInText("X$pan"))
        assertEquals("3782 82**** *0005", TlvProtocol.maskCardNumbersInText("3782 822463 10005"))
    }

    // --- end to end through the client, a fake terminal and captured stdout/stderr ---

    /** Fake terminal: answers each request in [replies] in turn and records what it received. */
    private fun terminal(server: ServerSocket, replies: List<String>, received: MutableList<String>) = thread {
        server.accept().use { socket ->
            val input = socket.getInputStream()
            val buf = ByteArray(4096)
            for (r in replies) {
                val n = input.read(buf)
                if (n <= 0) break
                received += String(buf, 0, n)
                socket.getOutputStream().write(r.toByteArray())
            }
            Thread.sleep(300)
        }
    }

    private fun <T> capturingOutput(block: () -> T): Pair<T, String> {
        val captured = ByteArrayOutputStream()
        val out = System.out
        val err = System.err
        val tee = PrintStream(captured, true)
        System.setOut(tee); System.setErr(tee)
        try {
            return block() to captured.toString()
        } finally {
            System.setOut(out); System.setErr(err)
        }
    }

    @Test
    fun payment_noFullCardNumberInResultReceiptsRequestsOrOutput() {
        // processPayment fires a gateway notification; never let a test run reach it
        System.setProperty("https.proxyHost", "127.0.0.1")
        System.setProperty("https.proxyPort", "9")
        val server = ServerSocket(0)
        val received = mutableListOf<String>()
        val t = terminal(server, listOf(reply("101"), reply("102")), received)
        val client = NapsPayClient(NapsConfig(host = "127.0.0.1", port = server.localPort))

        val (result, output) = capturingOutput {
            runBlocking { client.processPayment(PaymentRequest(10.00, "01", "00001")) }
        }
        t.join(); server.close()

        assertTrue(result.isApproved())
        assertEquals("516794******3315", result.maskedCardNumber)
        assertNoPan("PaymentResult", result.toString())
        assertNoPan("merchant receipt", result.merchantReceipt!!.toPlainText())
        assertNoPan("customer receipt", result.customerReceipt!!.toFormattedText())
        assertEquals("CARTE 5167 94** **** 3315", result.customerReceipt!!.lines[1].text)
        // TM 002 echoes NCAR: it must be the masked value
        val tm002 = received[1]
        assertTrue(tm002.contains(TlvProtocol.buildField(TlvProtocol.Tags.NCAR, "516794******3315")))
        received.forEach { assertNoPan("request frame", it) }
        assertNoPan("stdout/stderr", output)
    }

    @Test
    fun void_and_duplicate_noFullCardNumber() {
        val server = ServerSocket(0)
        val received = mutableListOf<String>()
        val t = terminal(server, listOf(reply("103"), reply("104")), received)
        val client = NapsPayClient(NapsConfig(host = "127.0.0.1", port = server.localPort))
        val (void, out1) = capturingOutput {
            runBlocking { client.cancelPayment("000123", "01", "00001", "000045", 1000L) }
        }
        t.join(); server.close()
        assertTrue(void.success)
        assertNoPan("CancellationResult", void.toString())
        assertNoPan("void receipts", void.merchantReceipt!!.toPlainText() + void.customerReceipt!!.toPlainText())
        received.forEach { assertNoPan("request frame", it) }
        assertNoPan("stdout/stderr", out1)

        val server2 = ServerSocket(0)
        val t2 = terminal(server2, listOf(reply("108")), mutableListOf())
        val dup = runBlocking {
            NapsPayClient(NapsConfig(host = "127.0.0.1", port = server2.localPort)).printDuplicate("01", "00001")
        }
        t2.join(); server2.close()
        assertNoPan("DuplicateReceiptResult", dup.toString())
    }

    @Test
    fun rawFrameFromTransportIsMasked() = runBlocking {
        val server = ServerSocket(0)
        val t = terminal(server, listOf(reply("101")), mutableListOf())
        val conn = NapsConnection(NapsConfig(host = "127.0.0.1", port = server.localPort))
        conn.connect()
        val raw = conn.sendAndReceive("001003001", 5_000)
        conn.disconnect()
        t.join(); server.close()
        assertNoPan("raw reply", raw)
        assertEquals(reply("101").length, raw.length)
    }

    @Test
    fun errorMessageNeverCarriesTheCardNumber() {
        val server = ServerSocket(0)
        // Approved Phase 1 without STAN → NapsError("Missing STAN")
        val t = terminal(server, listOf(reply("101", stan = null)), mutableListOf())
        val client = NapsPayClient(NapsConfig(host = "127.0.0.1", port = server.localPort))
        try {
            runBlocking { client.processPayment(PaymentRequest(10.00, "01", "00001")) }
            fail("expected NapsError")
        } catch (e: NapsError) {
            var c: Throwable? = e
            while (c != null) { assertNoPan("exception", c.toString()); c = c.cause }
            assertNoPan("stack trace", e.stackTraceToString())
        }
        t.join(); server.close()
    }
}
