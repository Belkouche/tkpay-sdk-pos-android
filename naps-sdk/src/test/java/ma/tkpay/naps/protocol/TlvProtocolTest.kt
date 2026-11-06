package ma.tkpay.naps.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the NAPS TLV v2 protocol behaviour.
 */
class TlvProtocolTest {

    // --- Confirmation request (TM=002) enrichment, spec §4.2 ---

    @Test
    fun confirmationRequest_omitsOptionalFieldsWhenNotProvided() {
        val msg = TlvProtocol.buildConfirmationRequest(
            stan = "000123",
            ncai = "0100001",
            sequence = "000045"
        )
        val fields = TlvProtocol.parse(msg)

        assertEquals(TlvProtocol.MessageTypes.CONFIRMATION_REQUEST, fields[TlvProtocol.Tags.TM])
        assertEquals("000123", fields[TlvProtocol.Tags.STAN])
        assertEquals("0100001", fields[TlvProtocol.Tags.NCAI])
        assertEquals("000045", fields[TlvProtocol.Tags.NS])
        // Optional coherence fields must be absent
        assertFalse("MT should be absent", fields.containsKey(TlvProtocol.Tags.MT))
        assertFalse("DE should be absent", fields.containsKey(TlvProtocol.Tags.DE))
        assertFalse("NCAR should be absent", fields.containsKey(TlvProtocol.Tags.NCAR))
        assertFalse("DAEX should be absent", fields.containsKey(TlvProtocol.Tags.DAEX))
    }

    @Test
    fun confirmationRequest_carriesAmountCurrencyNcarDaexWhenProvided() {
        val msg = TlvProtocol.buildConfirmationRequest(
            stan = "000123",
            ncai = "0100001",
            sequence = "000045",
            amountCentimes = 10000L,
            ncar = "516794******3315",
            daex = "3010"
        )
        val fields = TlvProtocol.parse(msg)

        assertEquals("10000", fields[TlvProtocol.Tags.MT])
        assertEquals(TlvProtocol.Currency.MAD, fields[TlvProtocol.Tags.DE])
        assertEquals("516794******3315", fields[TlvProtocol.Tags.NCAR])
        assertEquals("3010", fields[TlvProtocol.Tags.DAEX])
        assertEquals("000123", fields[TlvProtocol.Tags.STAN])
    }

    @Test
    fun confirmationRequest_fieldOrderMatchesReference() {
        // Expected order: TM, NCAI, NS, MT, DE, STAN, NCAR, DAEX, DA, HE
        val msg = TlvProtocol.buildConfirmationRequest(
            stan = "000123",
            ncai = "0100001",
            sequence = "000045",
            amountCentimes = 10000L,
            ncar = "516794******3315",
            daex = "3010"
        )
        val expectedOrder = listOf(
            TlvProtocol.Tags.TM,
            TlvProtocol.Tags.NCAI,
            TlvProtocol.Tags.NS,
            TlvProtocol.Tags.MT,
            TlvProtocol.Tags.DE,
            TlvProtocol.Tags.STAN,
            TlvProtocol.Tags.NCAR,
            TlvProtocol.Tags.DAEX,
            TlvProtocol.Tags.DA,
            TlvProtocol.Tags.HE
        )
        assertEquals(expectedOrder, tagSequence(msg))
    }

    /** Walk a TLV frame structurally and return its tags in wire order. */
    private fun tagSequence(msg: String): List<String> {
        val tags = mutableListOf<String>()
        var i = 0
        while (i + 6 <= msg.length) {
            val tag = msg.substring(i, i + 3)
            val len = msg.substring(i + 3, i + 6).toInt()
            tags.add(tag)
            i += 6 + len
        }
        return tags
    }

    @Test
    fun confirmationRequest_customAmountOnlyEmitsMtAndDe() {
        val msg = TlvProtocol.buildConfirmationRequest(
            stan = "000123",
            ncai = "0100001",
            sequence = "000045",
            amountCentimes = 250L
        )
        val fields = TlvProtocol.parse(msg)
        assertEquals("250", fields[TlvProtocol.Tags.MT])
        assertEquals(TlvProtocol.Currency.MAD, fields[TlvProtocol.Tags.DE])
        assertFalse(fields.containsKey(TlvProtocol.Tags.NCAR))
        assertFalse(fields.containsKey(TlvProtocol.Tags.DAEX))
    }

    // --- Terminator handling (v2 '!' and v1 '?') ---

    @Test
    fun parse_stopsAtV2Terminator() {
        val raw = TlvProtocol.buildField(TlvProtocol.Tags.CR, "000") + "!GARBAGE_AFTER"
        val fields = TlvProtocol.parse(raw)
        assertEquals("000", fields[TlvProtocol.Tags.CR])
        assertEquals(1, fields.size)
    }

    @Test
    fun parse_stopsAtV1Terminator() {
        val raw = TlvProtocol.buildField(TlvProtocol.Tags.CR, "116") + "?LEFTOVER"
        val fields = TlvProtocol.parse(raw)
        assertEquals("116", fields[TlvProtocol.Tags.CR])
        assertEquals(1, fields.size)
    }

    @Test
    fun parse_ignoresReceiptContentAfterTerminator() {
        val body = TlvProtocol.buildField(TlvProtocol.Tags.TM, "101") +
                   TlvProtocol.buildField(TlvProtocol.Tags.CR, "000") +
                   TlvProtocol.buildField(TlvProtocol.Tags.STAN, "000777")
        val fields = TlvProtocol.parse("$body!some ! receipt ? text")
        assertEquals("101", fields[TlvProtocol.Tags.TM])
        assertEquals("000", fields[TlvProtocol.Tags.CR])
        assertEquals("000777", fields[TlvProtocol.Tags.STAN])
    }

    @Test
    fun parse_withoutTerminatorStillParses() {
        val raw = TlvProtocol.buildField(TlvProtocol.Tags.CR, "000")
        val fields = TlvProtocol.parse(raw)
        assertEquals("000", fields[TlvProtocol.Tags.CR])
    }

    // --- Response-code describe (spec §6) ---

    @Test
    fun describeResponse_knownApprovedCodes() {
        assertEquals("Approved", TlvProtocol.describeResponse("000"))
        assertEquals("Approved with identification", TlvProtocol.describeResponse("001"))
        assertEquals("Approved (VIP)", TlvProtocol.describeResponse("003"))
        assertEquals("Approved, microchip update required", TlvProtocol.describeResponse("007"))
    }

    @Test
    fun describeResponse_knownDeclineCodes() {
        assertEquals("Do not honour", TlvProtocol.describeResponse("100"))
        assertEquals("Card expired", TlvProtocol.describeResponse("101"))
        assertEquals("Insufficient funds", TlvProtocol.describeResponse("116"))
        assertEquals("Incorrect PIN", TlvProtocol.describeResponse("117"))
        assertEquals("Transaction not permitted at terminal", TlvProtocol.describeResponse("120"))
        assertEquals("Invalid CVV", TlvProtocol.describeResponse("183"))
        assertEquals("Reversal accepted", TlvProtocol.describeResponse("480"))
        assertEquals("Transaction already cancelled", TlvProtocol.describeResponse("482"))
        assertEquals("System failure", TlvProtocol.describeResponse("909"))
        assertEquals("Issuer did not respond in time", TlvProtocol.describeResponse("911"))
        assertEquals("Server processing error", TlvProtocol.describeResponse("995"))
    }

    @Test
    fun describeResponse_unknownCodeFallsBack() {
        assertEquals("Unknown response code: 999", TlvProtocol.describeResponse("999"))
        assertEquals("Unknown response code: (none)", TlvProtocol.describeResponse(null))
    }

    @Test
    fun isApproved_onlyApprovedCodes() {
        assertTrue(TlvProtocol.isApproved("000"))
        assertTrue(TlvProtocol.isApproved("001"))
        assertTrue(TlvProtocol.isApproved("003"))
        assertTrue(TlvProtocol.isApproved("007"))
        assertFalse(TlvProtocol.isApproved("100"))
        assertFalse(TlvProtocol.isApproved("116"))
        assertFalse(TlvProtocol.isApproved(null))
    }

    // --- PAN masking on parse (existing security behaviour, still holds) ---

    @Test
    fun parse_masksPanInNcar() {
        val raw = TlvProtocol.buildField(TlvProtocol.Tags.NCAR, "5167940123453315") + "!"
        val fields = TlvProtocol.parse(raw)
        assertEquals("516794******3315", fields[TlvProtocol.Tags.NCAR])
    }

    // --- Management frames (TM=008/009/010/012/013) carry NS, spec v5.4.4 ---

    @Test
    fun managementRequests_carrySequenceInReferenceOrder() {
        val frames = mapOf(
            TlvProtocol.MessageTypes.NETWORK_TEST_REQUEST to TlvProtocol.buildNetworkTestRequest("0100001", "000042"),
            TlvProtocol.MessageTypes.TOTALS_REQUEST to TlvProtocol.buildTotalsRequest("0100001", "000042"),
            TlvProtocol.MessageTypes.RESET_REQUEST to TlvProtocol.buildResetRequest("0100001", "000042"),
            TlvProtocol.MessageTypes.DUPLICATE_REQUEST to TlvProtocol.buildDuplicateRequest("0100001", sequence = "000042")
        )
        for ((tm, msg) in frames) {
            val expected = listOf(
                TlvProtocol.Tags.TM, TlvProtocol.Tags.NCAI, TlvProtocol.Tags.NS,
                TlvProtocol.Tags.DA, TlvProtocol.Tags.HE
            )
            assertEquals("TM=$tm", expected, tagSequence(msg))
            val fields = TlvProtocol.parse(msg)
            assertEquals(tm, fields[TlvProtocol.Tags.TM])
            assertEquals("000042", fields[TlvProtocol.Tags.NS])
        }
    }

    @Test
    fun duplicateRequest_appendsOptionalStan() {
        val fields = TlvProtocol.parse(TlvProtocol.buildDuplicateRequest("0100001", "000777", "000042"))
        assertEquals("000777", fields[TlvProtocol.Tags.STAN])
        assertEquals("000042", fields[TlvProtocol.Tags.NS])
    }

    @Test
    fun referencingRequest_carriesTideDefaultMerchant() {
        val msg = TlvProtocol.buildReferencingRequest("0100001", "000042")
        assertEquals(TlvProtocol.Tags.TIDE, tagSequence(msg).last())
        assertEquals(TlvProtocol.Tide.MERCHANT, TlvProtocol.parse(msg)[TlvProtocol.Tags.TIDE])
        assertEquals("01", TlvProtocol.Tide.MERCHANT)
    }

    @Test
    fun referencingRequest_carriesRechargeTide() {
        val msg = TlvProtocol.buildReferencingRequest("0100001", "000042", TlvProtocol.Tide.RECHARGE)
        assertEquals("02", TlvProtocol.parse(msg)[TlvProtocol.Tags.TIDE])
    }

    // --- Amount conversion ---

    @Test
    fun toCentimes_roundsInsteadOfTruncating() {
        assertEquals(1999L, TlvProtocol.toCentimes(19.99))
        assertEquals(15000L, TlvProtocol.toCentimes(150.0))
        assertEquals(1L, TlvProtocol.toCentimes(0.01))
    }

    @Test
    fun paymentRequest_amountUsesRoundedCentimes() {
        val fields = TlvProtocol.parse(TlvProtocol.buildPaymentRequest(19.99, "0100001", "000001"))
        assertEquals("1999", fields[TlvProtocol.Tags.MT])
        assertEquals(TlvProtocol.Currency.MAD, fields[TlvProtocol.Tags.DE])
    }
}
