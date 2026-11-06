package ma.tkpay.naps

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.connection.NapsConnection
import ma.tkpay.naps.models.ErrorCode
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.PaymentResult
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
 * no_confirm. The payment flow is driven through NapsConnection + TlvProtocol (the
 * same frames and result mapping processPayment uses) so no gateway notification
 * is fired from tests.
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

    @Test
    fun approve_twoPhaseVoid() = runBlocking {
        requireMode("approve")
        val result = client.cancelPayment("000123", "01", "00001", "000001", 1999L)
        assertTrue(result.error, result.success)
        assertEquals("000", result.responseCode)
        assertNotNull(result.stan)
    }

    @Test
    fun approve_managementOperations() = runBlocking {
        requireMode("approve")
        val network = client.networkTest("01", "00001")
        assertTrue(network.error, network.success)

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
}
