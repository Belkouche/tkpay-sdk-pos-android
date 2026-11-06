package ma.tkpay.naps

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.connection.NapsConnection
import ma.tkpay.naps.protocol.TlvProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests against tkpay-naps-emulator. Skipped unless NAPS_EMULATOR_PORT is set:
 *
 *   python3 tkpay-naps-emulator/naps_emulator.py --port 4454 --strict-confirm
 *   NAPS_EMULATOR_PORT=4454 ./gradlew :naps-sdk:testDebugUnitTest
 *
 * The payment flow is driven through NapsConnection + TlvProtocol (the same frames
 * processPayment sends) so no gateway notification is fired from tests.
 */
class EmulatorIntegrationTest {

    private lateinit var config: NapsConfig
    private lateinit var client: NapsPayClient

    @Before
    fun setUp() {
        val port = System.getenv("NAPS_EMULATOR_PORT")?.toIntOrNull()
        assumeTrue("NAPS_EMULATOR_PORT not set", port != null)
        config = NapsConfig(host = "127.0.0.1", port = port!!, timeout = 10_000)
        client = NapsPayClient(config)
    }

    @Test
    fun twoPhasePayment_confirmationEchoesCoherenceFields() = runBlocking {
        val conn = NapsConnection(config)
        conn.connect()
        try {
            val phase1 = TlvProtocol.parse(
                conn.sendAndReceive(TlvProtocol.buildPaymentRequest(19.99, "0100001", "000001"), config.timeout)
            )
            assertEquals(TlvProtocol.MessageTypes.PAYMENT_RESPONSE, phase1[TlvProtocol.Tags.TM])
            assertEquals("000", phase1[TlvProtocol.Tags.CR])
            assertEquals("516794******3315", phase1[TlvProtocol.Tags.NCAR])

            // Phase 2 on the same connection; --strict-confirm rejects (CR=307) on MT/NCAR mismatch
            val confirm = TlvProtocol.buildConfirmationRequest(
                stan = phase1.getValue(TlvProtocol.Tags.STAN),
                ncai = "0100001",
                sequence = "000001",
                amountCentimes = TlvProtocol.toCentimes(19.99),
                ncar = phase1[TlvProtocol.Tags.NCAR],
                daex = phase1[TlvProtocol.Tags.DAEX]
            )
            val phase2 = TlvProtocol.parse(conn.sendAndReceive(confirm, config.confirmationTimeout))
            assertEquals(TlvProtocol.MessageTypes.CONFIRMATION_RESPONSE, phase2[TlvProtocol.Tags.TM])
            assertEquals("000", phase2[TlvProtocol.Tags.CR])

            val result = client.buildSuccessfulResult(phase1, phase2)
            assertTrue(result.isApproved())
            assertEquals(phase1[TlvProtocol.Tags.STAN], result.stan)
            assertNotNull(result.merchantReceipt)
            assertNotNull(result.customerReceipt)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun cancellation_carriesAmountAndIsAccepted() = runBlocking {
        val conn = NapsConnection(config)
        conn.connect()
        try {
            val fields = TlvProtocol.parse(
                conn.sendAndReceive(
                    TlvProtocol.buildCancellationRequest("000123", "0100001", "000001", 1999L),
                    config.timeout
                )
            )
            assertEquals(TlvProtocol.MessageTypes.CANCELLATION_RESPONSE, fields[TlvProtocol.Tags.TM])
            assertEquals("000", fields[TlvProtocol.Tags.CR])
            assertEquals("1999", fields[TlvProtocol.Tags.MT])
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun managementOperations() = runBlocking {
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
    }
}
