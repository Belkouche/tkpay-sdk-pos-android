package ma.tkpay.naps

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.connection.NapsConnection
import ma.tkpay.naps.models.CancellationResult
import ma.tkpay.naps.models.ErrorCode
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.PaymentResult
import ma.tkpay.naps.models.Receipt
import ma.tkpay.naps.protocol.TlvProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Integration tests against tkpay-naps-emulator. Skipped unless NAPS_EMULATOR_PORT is set.
 * The emulator runs one mode per process, so NAPS_EMULATOR_MODE selects the tests to run:
 *
 *   python3 naps_emulator.py --port 4454 --strict-confirm
 *   NAPS_EMULATOR_PORT=4454 NAPS_EMULATOR_MODE=approve ./gradlew :naps-sdk:testDebugUnitTest
 *
 * Modes covered: approve, decline, error (--code 116), confirm_error (--code 482),
 * no_confirm, and the void modes void_refuse (--code 482), void_480, void_995,
 * void_no_confirm. The payment flow is driven through NapsConnection + TlvProtocol (the
 * same frames and result mapping processPayment uses).
 */
class EmulatorIntegrationTest {

    private val port = System.getenv("NAPS_EMULATOR_PORT")?.toIntOrNull()
    private val mode = System.getenv("NAPS_EMULATOR_MODE") ?: "approve"
    private val config by lazy {
        NapsConfig(host = "127.0.0.1", port = port!!, timeout = 10_000, confirmationTimeout = 3_000)
    }
    private val client by lazy { NapsPayClient(config) }

    private fun requireMode(expected: String) {
        assumeTrue("NAPS_EMULATOR_PORT not set", port != null)
        assumeTrue("emulator mode is $mode", mode == expected)
    }

    /** Phase 1 → (if approved) Phase 2 on the same connection, mapped like processPayment. */
    private fun pay(amount: Double): PaymentResult = runBlocking {
        val conn = NapsConnection(config)
        conn.connect()
        try {
            val phase1 = TlvProtocol.parse(
                conn.sendAndReceive(TlvProtocol.buildPaymentRequest(amount, "0100001", "000001"), config.timeout)
            )
            assertEquals(TlvProtocol.MessageTypes.PAYMENT_RESPONSE, phase1[TlvProtocol.Tags.TM])
            val cr = phase1.getValue(TlvProtocol.Tags.CR)
            if (cr != "000") return@runBlocking client.buildFailedResult(cr, phase1)

            // --strict-confirm rejects (CR=307) on MT/NCAR mismatch
            val confirm = TlvProtocol.buildConfirmationRequest(
                stan = phase1.getValue(TlvProtocol.Tags.STAN),
                ncai = "0100001",
                sequence = "000001",
                amountCentimes = TlvProtocol.toCentimes(amount),
                ncar = phase1[TlvProtocol.Tags.NCAR],
                daex = phase1[TlvProtocol.Tags.DAEX]
            )
            val phase2 = TlvProtocol.parse(conn.sendAndReceive(confirm, config.confirmationTimeout))
            assertEquals(TlvProtocol.MessageTypes.CONFIRMATION_RESPONSE, phase2[TlvProtocol.Tags.TM])
            client.buildPaymentResult(phase1, phase2)
        } finally {
            conn.disconnect()
        }
    }

    // --- approve (with --strict-confirm) ---

    @Test
    fun approve_twoPhasePaymentWithCoherenceFields() {
        requireMode("approve")
        val result = pay(19.99)
        assertTrue(result.error, result.isApproved())
        assertNotNull(result.stan)
        assertEquals("516794******3315", result.maskedCardNumber)
        assertNotNull(result.merchantReceipt)
        assertNotNull(result.customerReceipt)
    }

    /** Two-phase void TM=003 → TM=004 for STAN 000123, sequence 000045, 19.99 MAD. */
    private fun void(): CancellationResult = runBlocking {
        client.cancelPayment("000123", "01", "00001", "000045", 1999L)
    }

    @Test
    fun approve_twoPhaseVoid() {
        requireMode("approve")
        val result = void()
        assertTrue(result.error, result.success)
        assertFalse(result.pending)
        assertEquals("000", result.responseCode)
        assertNotNull(result.stan)
        assertNotNull(result.merchantReceipt)
    }

    @Test
    fun approve_managementOperations() = runBlocking {
        requireMode("approve")
        val network = client.networkTest("01", "00001")
        assertFalse(network.success) // TCP: not sent (Sunmi NapsPay answers TM=009 with 902)
        assertEquals("UNSUPPORTED", network.responseCode)

        val referencing = client.referencing("01", "00001", TlvProtocol.Tide.RECHARGE)
        assertTrue(referencing.error, referencing.success)
        assertEquals("02", referencing.parameterSet)
        assertNotNull(referencing.receipt)

        val totals = client.totals("01", "00001")
        assertTrue(totals.error, totals.success)
        assertNotNull(totals.receipt)

        val duplicate = client.printDuplicate("01", "00001")
        assertTrue(duplicate.error, duplicate.success)
        assertNotNull(duplicate.merchantReceipt)

        val reset = client.resetPinPad("01", "00001")
        assertTrue(reset.error, reset.success)

        val settlement = client.settlement("01", "00001")
        assertTrue(settlement.error, settlement.success)
    }

    // --- decline paths ---

    @Test
    fun decline_phase1RefusalReturnsFailedResultWithReceipt() {
        requireMode("decline")
        val result = pay(50.0)
        assertFalse(result.success)
        assertEquals("100", result.responseCode)
        assertEquals("Do not honour — code: 100", result.error)
        assertNotNull(result.merchantReceipt)
    }

    @Test
    fun error_phase1CodeIsReported() {
        requireMode("error")
        val result = pay(50.0)
        assertFalse(result.success)
        assertEquals("116", result.responseCode)
        assertEquals("Insufficient funds — code: 116", result.error)
    }

    @Test
    fun confirmError_phase2RefusalIsAFailedPayment() {
        requireMode("confirm_error")
        val result = pay(50.0)
        assertFalse(result.success)
        assertFalse(result.isApproved())
        assertEquals("482", result.responseCode)
        assertEquals("Transaction already cancelled", result.error)
    }

    @Test
    fun noConfirm_phase2TimesOut() {
        requireMode("no_confirm")
        try {
            pay(50.0)
            fail("expected timeout")
        } catch (e: NapsError) {
            assertEquals(ErrorCode.TIMEOUT, e.code)
        }
    }

    // --- void (TM=003 → TM=004) outcomes, spec §4.3/§4.4 ---

    @Test
    fun void480_isSuccess() {
        requireMode("void_480")
        val result = void()
        assertTrue(result.error, result.success)
        assertFalse(result.pending)
        assertEquals("480", result.responseCode)
    }

    @Test
    fun void995_isPendingWithStanAndReceipt() {
        requireMode("void_995")
        val result = void()
        assertFalse(result.success)
        assertTrue(result.pending)
        assertEquals("995", result.responseCode)
        assertEquals("000123", result.stan)
        assertNotNull(result.merchantReceipt)
    }

    @Test
    fun voidNoConfirm_isPendingWithStanAndReceipt() {
        requireMode("void_no_confirm")
        val result = void()
        assertFalse(result.success)
        assertTrue(result.pending)
        assertEquals("", result.responseCode)
        assertEquals("000123", result.stan)
        assertNotNull(result.merchantReceipt)
    }

    @Test
    fun voidRefuse_isFailureNotPending() {
        requireMode("void_refuse")
        val result = void()
        assertFalse(result.success)
        assertFalse(result.pending)
        assertEquals("482", result.responseCode)
    }

    // --- receipts (DP, spec §5): every line, in order, as the terminal sent it ---

    /** Expected line → regex; `<DATE>` = dd/MM/yyyy HH:mm:ss, `<N>` = digits, the rest literal. */
    private fun toRegex(line: String): Regex {
        val sb = StringBuilder()
        var last = 0
        for (m in Regex("<DATE>|<N>").findAll(line)) {
            sb.append(Regex.escape(line.substring(last, m.range.first)))
            sb.append(if (m.value == "<DATE>") "\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}" else "\\d+")
            last = m.range.last + 1
        }
        sb.append(Regex.escape(line.substring(last)))
        return Regex(sb.toString())
    }

    private fun assertReceipt(expected: List<String>, receipt: Receipt?) {
        assertNotNull("receipt missing", receipt)
        val actual = receipt!!.toPlainText().lines().map { it.trim() }
        assertEquals("line count, got:\n${actual.joinToString("\n")}", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertTrue("line ${i + 1}: '$a' does not match '$e'", toRegex(e).matches(a))
        }
    }

    private fun saleReceipt(amount: String, stan: String, operation: String, copy: String) = listOf(
        "TKpay", SEP, "<DATE>", "TKPAY DEMO", "CASABLANCA", SEP,
        "VISA", "516794******3315", "Terminal: 00000001",
        "Transaction: $stan", "Autorisation: <N>", "STAN: $stan", SEP,
        "MONTANT: $amount MAD", SEP, operation, copy
    )

    @Test
    fun approve_paymentReceiptsMerchantAndCustomer() {
        requireMode("approve")
        val result = pay(10.00)
        assertTrue(result.error, result.isApproved())
        assertReceipt(saleReceipt("10.00", result.stan!!, "DEBIT", "Copie Commercant"), result.merchantReceipt)
        assertReceipt(saleReceipt("10.00", result.stan!!, "DEBIT", "Copie Client"), result.customerReceipt)
    }

    @Test
    fun approve_voidReceiptsMerchantAndCustomer() {
        requireMode("approve")
        val result = runBlocking { client.cancelPayment("000123", "01", "00001", "000045", 1000L) }
        assertTrue(result.error, result.success)
        // TM 103 copy is about the original sale (STAN 000123), TM 104 copy carries the void STAN
        assertReceipt(saleReceipt("10.00", "000123", "ANNULATION", "Copie Commercant"), result.merchantReceipt)
        assertReceipt(saleReceipt("10.00", result.stan!!, "ANNULATION", "Copie Client"), result.customerReceipt)
    }

    @Test
    fun approve_duplicateTotalsAndReferencingReceipts() = runBlocking {
        requireMode("approve")
        assertReceipt(
            saleReceipt("0.00", "<N>", "DEBIT", "Copie Commercant"),
            client.printDuplicate("01", "00001").merchantReceipt
        )
        assertReceipt(
            listOf("TKpay", SEP, "<DATE>", "TOTAUX DU JOUR", SEP, "VENTES:     0000000",
                "ANNULATIONS:       0", SEP, "MONTANT:   0.00 MAD", SEP),
            client.totals("01", "00001").receipt
        )
        assertReceipt(
            listOf("TKpay", SEP, "<DATE>", "PARAMETRES MARCHAND", SEP, "MID: 000000000001",
                "TID: 00000001", "DEVISE: MAD (504)", SEP, "TKPAY DEMO", "CASABLANCA", SEP),
            client.referencing("01", "00001").receipt
        )
    }

    @Test
    fun decline_receiptAsSent() {
        requireMode("decline")
        val result = pay(10.00)
        assertReceipt(
            listOf("TKpay", SEP, "<DATE>", "TKPAY DEMO", "CASABLANCA", SEP, "Terminal: 00000001",
                "STAN: ${result.stan}", SEP, "TRANSACTION REFUSEE", "Code: 100", SEP),
            result.merchantReceipt
        )
    }

    private companion object {
        const val SEP = "--------------------------------"
    }
}
