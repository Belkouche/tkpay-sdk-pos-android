package ma.tkpay.naps.protocol

import ma.tkpay.naps.models.NapsError
import java.text.SimpleDateFormat
import java.util.*

/**
 * TLV Protocol implementation for NAPS Pay M2M (NapsPay v5.4.4)
 *
 * Tag-Length-Value format:
 * - TAG: 3 digits
 * - LENGTH: 3 digits
 * - VALUE: variable length
 *
 * Example: 001003001 = Tag 001, Length 003, Value "001"
 */
object TlvProtocol {

    /**
     * TLV Tag codes (3-digit numeric)
     * Mapping from decompiled NAPS Pay v5.0.3 ECRTags.java
     */
    object Tags {
        const val TM = "001"      // Message Type (3 chars)
        const val MT = "002"      // Amount (12 chars)
        const val NCAI = "003"    // Terminal Number - Register(2) + Cashier(5) (7 chars)
        const val NS = "004"      // Sequence Number (6 chars)
        const val NSA = "005"     // Cancellation Sequence Number (6 chars)
        const val NHC = "006"     // Hostess Number (2 chars)
        const val NCAR = "007"    // Card Number (16 chars) — masked on read, first 6 + last 4
        const val STAN = "008"    // System Trace Audit Number (6 chars)
        const val NA = "009"      // Authorization Number (6 chars)
        const val DP = "010"      // Printable Data / Receipt (3500 chars)
        const val CB = "011"      // Barcode (100 chars)
        const val DE = "012"      // Currency Code (3 chars)
        const val CR = "013"      // Response Code (3 chars)
        const val DA = "014"      // Date (8 chars, DDMMYYYY)
        const val HE = "015"      // Time (6 chars, HHMMSS)
        const val NPRT = "016"    // Cardholder Name (48 chars)
        const val DAEX = "017"    // Expiration Date (4 chars, YYMM)
        const val DATR = "018"    // Transaction Date (8 chars, DDMMYYYY)
        const val HETR = "019"    // Transaction Time (6 chars, HHMMSS)
        const val TIDE = "020"    // Ticket Type (2 chars)
        const val TYPA = "021"    // Transaction Type (1 char)
        const val RE = "022"      // Receipt Data (256 chars)
        const val RECB = "023"    // Receipt Copy (25 chars)
        const val REQU = "024"    // Request Type (2 chars)
        const val RESE = "025"    // Response Message (25 chars)
        const val RECO = "026"    // Receipt Confirmation (25 chars)
        const val RERA = "027"    // Response Reason (2 chars)
        const val MDLC = "028"    // Model Code (3 chars)
        const val EM = "040"      // Entry Mode (3 chars)
    }

    /**
     * Message Types
     */
    object MessageTypes {
        const val PAYMENT_REQUEST = "001"
        const val PAYMENT_RESPONSE = "101"
        const val CONFIRMATION_REQUEST = "002"
        const val CONFIRMATION_RESPONSE = "102"
        const val CANCELLATION_REQUEST = "003"
        const val CANCELLATION_RESPONSE = "103"
        const val CANCELLATION_CONFIRMATION_REQUEST = "004"
        const val CANCELLATION_CORRECTION = "104"
        const val DUPLICATE_REQUEST = "008"
        const val DUPLICATE_RESPONSE = "108"
        const val NETWORK_TEST_REQUEST = "009"
        const val NETWORK_TEST_RESPONSE = "109"
        const val SETTLEMENT_REQUEST = "010"
        const val SETTLEMENT_RESPONSE = "110"
        const val TOTALS_REQUEST = "010"      // Totals report (shares TM with settlement)
        const val TOTALS_RESPONSE = "110"
        const val RESET_REQUEST = "012"
        const val RESET_RESPONSE = "112"
        const val REFERENCING_REQUEST = "013"
        const val REFERENCING_RESPONSE = "113"
    }

    /**
     * Currency Codes
     */
    object Currency {
        const val MAD = "504"  // Moroccan Dirham
    }

    /**
     * TIDE (020) parameter sets for referencing (TM=013)
     */
    object Tide {
        const val MERCHANT = "01"  // Merchant parameter set
        const val RECHARGE = "02"  // Recharge parameter set
    }

    /**
     * End-of-message terminators. NAPS TLV v2 terminates frames with '!';
     * v1 terminates with '?'. Both are recognised so the parser and read
     * loop are protocol-version agnostic.
     */
    const val TERMINATOR_V2 = '!'
    const val TERMINATOR_V1 = '?'

    /**
     * CR (013) response codes that mean the transaction was approved.
     * 000 always means success; 001/003/007 are also approvals.
     */
    val APPROVED_CODES = setOf("000", "001", "003", "007")

    /**
     * CR (013) response codes from the NAPS TLV v2 spec §6. Code 000 always
     * means success; 001/003/007 are also approvals. All others describe a
     * specific condition.
     */
    val RESPONSE_CODES: Map<String, String> = mapOf(
        "000" to "Approved",
        "001" to "Approved with identification",
        "003" to "Approved (VIP)",
        "007" to "Approved, microchip update required",
        "100" to "Do not honour",
        "101" to "Card expired",
        "102" to "Fraud suspected",
        "104" to "Restricted card use",
        "106" to "Allowed PIN tries exceeded",
        "107" to "Refer to card issuer",
        "108" to "Refer to issuer special conditions",
        "109" to "Invalid merchant",
        "110" to "Invalid amount",
        "111" to "Invalid card number",
        "112" to "PIN information required",
        "114" to "No account of requested type",
        "115" to "Requested function not supported",
        "116" to "Insufficient funds",
        "117" to "Incorrect PIN",
        "118" to "Card not registered",
        "119" to "Transaction not permitted to cardholder",
        "120" to "Transaction not permitted at terminal",
        "121" to "Withdrawal limit exceeded",
        "122" to "Security violation",
        "123" to "Withdrawal frequency exceeded",
        "125" to "Card not in service",
        "126" to "Invalid PIN format",
        "127" to "PIN length error",
        "128" to "PIN key sync error",
        "129" to "Card counterfeiting suspected",
        "180" to "Credit account does not exist",
        "181" to "Cheque account does not exist",
        "182" to "Savings account does not exist",
        "183" to "Invalid CVV",
        "184" to "Invalid date",
        "200" to "Do not honour — capture card",
        "201" to "Card expired — capture card",
        "202" to "Fraud suspected — capture card",
        "204" to "Restricted card — capture card",
        "205" to "Security service — capture card",
        "206" to "PIN tries exceeded — capture card",
        "207" to "Special conditions — capture card",
        "208" to "Lost card — capture card",
        "209" to "Stolen card — capture card",
        "210" to "Counterfeiting suspected — capture card",
        "280" to "Alternative amount cancelled",
        "299" to "Capture card",
        "300" to "Processing completed",
        "301" to "Not supported by recipient",
        "302" to "Record not found on file",
        "303" to "Duplicate record, old record replaced",
        "304" to "Field edit error",
        "305" to "File locked",
        "306" to "Processing failure",
        "307" to "Format error",
        "308" to "Duplicate processing, new record rejected",
        "309" to "File unknown",
        "381" to "Record not found. Account unsettled. Withdrawal not processed",
        "382" to "Balance record deleted",
        "383" to "Balance enquiry",
        "480" to "Reversal accepted",
        "481" to "Reversal incorrect",
        "482" to "Transaction already cancelled",
        "501" to "Reversal amount incorrect",
        "503" to "Totals not available",
        "581" to "Reconciliation complete",
        "582" to "Reconciliation procedure not available",
        "700" to "Top-ups fully reserved",
        "701" to "Top-ups partially reserved",
        "702" to "Top-ups not reserved",
        "750" to "Message or ECR request not authorised by centre",
        "800" to "Cut-off in progress",
        "880" to "Connection not accepted",
        "902" to "Invalid transaction",
        "908" to "Transaction recipient not referenced for routing",
        "909" to "System failure",
        "911" to "Issuer did not respond in time",
        "912" to "Card issuer unavailable",
        "992" to "Issuer not found",
        "993" to "PIN verification impossible",
        "994" to "Transaction processing error",
        "995" to "Server processing error"
    )

    /**
     * Human-readable description of a CR code, or a fallback string if unknown.
     */
    fun describeResponse(cr: String?): String =
        cr?.let { RESPONSE_CODES[it] } ?: run {
            "Unknown response code: ${cr ?: "(none)"}"
        }

    /**
     * Whether a CR code denotes an approved transaction.
     */
    fun isApproved(cr: String?): Boolean = cr != null && cr in APPROVED_CODES

    /**
     * Receipt Sub-tags (within DP/010)
     */
    object ReceiptTags {
        const val LINE_NUMBER = "030"    // 2 chars
        const val FORMAT = "031"         // S=Simple, G=Gras/Bold
        const val ALIGNMENT = "032"      // C=Center, D=Droite/Right, G=Gauche/Left
        const val CONTENT = "033"        // Variable length
    }

    /**
     * Build TLV field
     */
    fun buildField(tag: String, value: String): String {
        val length = value.length.toString().padStart(3, '0')
        return "$tag$length$value"
    }

    /**
     * Build payment request TLV
     *
     * Sends current date/time as DA (014) and HE (015),
     * plus transaction date/time as DATR (018) and HETR (019).
     */
    fun buildPaymentRequest(
        amount: Double,
        ncai: String,
        sequence: String
    ): String {
        val amountMinor = toCentimes(amount).toString()
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.PAYMENT_REQUEST) +
               buildField(Tags.MT, amountMinor) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.NS, sequence) +
               buildField(Tags.DE, Currency.MAD) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time) +
               buildField(Tags.DATR, dateTime.date) +
               buildField(Tags.HETR, dateTime.time)
    }

    /**
     * Convert a MAD amount to centimes, rounding to the nearest centime so
     * binary floating-point error (e.g. 19.99 * 100 = 1998.999…) never drops
     * a centime.
     */
    fun toCentimes(amount: Double): Long = Math.round(amount * 100)

    /**
     * Build cancellation request TLV (TM=003), spec v2 §4.3.
     *
     * [sequence] is the void's own NS (004), a new number independent of the payment; TM=004
     * must repeat it. [originalSequence], the payment's sequence, goes in NSA (005). NapsPay
     * v5.4.4+ requires the original amount (TAG 002). Field order matches the frame the Ingenico
     * Lane3600 accepted: TM, NCAI, NS, DA, HE, DATR, HETR, STAN, [MT], [NSA]. A frame with no NS
     * was refused by the Lane3600 with CR 307 (format error).
     */
    fun buildCancellationRequest(
        stan: String,
        ncai: String,
        sequence: String,
        amountCentimes: Long? = null,
        originalSequence: String? = null
    ): String {
        val dateTime = getCurrentDateTime()

        var msg = buildField(Tags.TM, MessageTypes.CANCELLATION_REQUEST) +
                  buildField(Tags.NCAI, ncai) +
                  buildField(Tags.NS, sequence) +
                  buildField(Tags.DA, dateTime.date) +
                  buildField(Tags.HE, dateTime.time) +
                  buildField(Tags.DATR, dateTime.date) +
                  buildField(Tags.HETR, dateTime.time) +
                  buildField(Tags.STAN, stan)
        if (amountCentimes != null) msg += buildField(Tags.MT, amountCentimes.toString())
        if (!originalSequence.isNullOrEmpty()) msg += buildField(Tags.NSA, originalSequence)
        return msg
    }

    /**
     * Build cancellation confirmation request TLV (TM=004), spec v2 §4.4.
     *
     * Sent on the same connection after receiving MT=103 from TM=003. Spec fields and order:
     * TM, NCAI, NS (same as TM=003), STAN, MT, DE, DATR, HETR, DA, HE. [amount], [currency],
     * [datr] and [hetr] are echoed from MT=103; omitting the amount is refused with CR=909 on
     * NapsPay v5.4.4+. No NSA: the sequence goes in NS.
     */
    fun buildCancellationConfirmation(
        stan: String,
        ncai: String,
        sequence: String,
        amount: String? = null,
        currency: String? = null,
        datr: String? = null,
        hetr: String? = null
    ): String {
        val dateTime = getCurrentDateTime()

        var msg = buildField(Tags.TM, MessageTypes.CANCELLATION_CONFIRMATION_REQUEST) +
                  buildField(Tags.NCAI, ncai) +
                  buildField(Tags.NS, sequence) +
                  buildField(Tags.STAN, stan)
        if (amount != null) {
            msg += buildField(Tags.MT, amount)
            msg += buildField(Tags.DE, if (currency.isNullOrEmpty()) "504" else currency)
        }
        if (!datr.isNullOrEmpty()) msg += buildField(Tags.DATR, datr)
        if (!hetr.isNullOrEmpty()) msg += buildField(Tags.HETR, hetr)
        msg += buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time)
        return msg
    }

    /**
     * Build a management-operation TLV frame: TM + NCAI + NS + DA + HE, followed
     * by optional [extraFields]. Used by the duplicate (008), network test (009),
     * totals (010), reset (012) and referencing (013) operations, which carry no amount.
     */
    fun buildManagementRequest(
        tm: String,
        ncai: String,
        sequence: String = "000001",
        extraFields: List<Pair<String, String>> = emptyList()
    ): String {
        val dateTime = getCurrentDateTime()

        var msg = buildField(Tags.TM, tm) +
                  buildField(Tags.NCAI, ncai) +
                  buildField(Tags.NS, sequence) +
                  buildField(Tags.DA, dateTime.date) +
                  buildField(Tags.HE, dateTime.time)
        for ((tag, value) in extraFields) msg += buildField(tag, value)
        return msg
    }

    /**
     * Build duplicate receipt request TLV (TM=008)
     */
    fun buildDuplicateRequest(ncai: String, stan: String? = null, sequence: String = "000001"): String =
        buildManagementRequest(
            MessageTypes.DUPLICATE_REQUEST, ncai, sequence,
            if (stan != null) listOf(Tags.STAN to stan) else emptyList()
        )

    /**
     * Build network test request TLV (TM=009)
     */
    fun buildNetworkTestRequest(ncai: String, sequence: String = "000001"): String =
        buildManagementRequest(MessageTypes.NETWORK_TEST_REQUEST, ncai, sequence)

    /**
     * Build totals report request TLV (TM=010)
     */
    fun buildTotalsRequest(ncai: String, sequence: String = "000001"): String =
        buildManagementRequest(MessageTypes.TOTALS_REQUEST, ncai, sequence)

    /**
     * Build reset PinPAD request TLV (TM=012)
     */
    fun buildResetRequest(ncai: String, sequence: String = "000001"): String =
        buildManagementRequest(MessageTypes.RESET_REQUEST, ncai, sequence)

    /**
     * Build referencing request TLV (TM=013)
     *
     * Carries the TIDE (020) selecting the parameter set to load:
     * [Tide.MERCHANT] ("01", default) or [Tide.RECHARGE] ("02").
     */
    fun buildReferencingRequest(
        ncai: String,
        sequence: String = "000001",
        tide: String = Tide.MERCHANT
    ): String =
        buildManagementRequest(MessageTypes.REFERENCING_REQUEST, ncai, sequence, listOf(Tags.TIDE to tide))

    /**
     * Build settlement request TLV (TM=010 — telecollecte)
     */
    fun buildSettlementRequest(ncai: String): String {
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.SETTLEMENT_REQUEST) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time)
    }

    /**
     * Build payment confirmation request TLV (TM=002)
     *
     * Per NAPS TLV v2 §4.2 the confirmation may carry, in addition to
     * TM/NCAI/NS/STAN/DA/HE, coherence fields echoed from the approved
     * Payment Response (TM=101):
     * - [amountCentimes] → MT (002) + DE (012) currency (default "504")
     * - [ncar]           → NCAR (007), the masked PAN
     * - [daex]           → DAEX (017), the card expiry (YYMM)
     *
     * Optional fields are omitted when null/blank, keeping older callers working.
     * Field order mirrors the reference: TM, NCAI, NS, [MT, DE], STAN, [NCAR],
     * [DAEX], DA, HE. DATR/HETR are not sent (they belong to the TM=001 request).
     */
    fun buildConfirmationRequest(
        stan: String,
        ncai: String,
        sequence: String,
        amountCentimes: Long? = null,
        currency: String = Currency.MAD,
        ncar: String? = null,
        daex: String? = null
    ): String {
        val dateTime = getCurrentDateTime()

        var msg = buildField(Tags.TM, MessageTypes.CONFIRMATION_REQUEST) +
                  buildField(Tags.NCAI, ncai) +
                  buildField(Tags.NS, sequence)

        if (amountCentimes != null) {
            msg += buildField(Tags.MT, amountCentimes.toString()) +
                   buildField(Tags.DE, currency)
        }

        msg += buildField(Tags.STAN, stan)

        if (!ncar.isNullOrEmpty()) msg += buildField(Tags.NCAR, ncar)
        if (!daex.isNullOrEmpty()) msg += buildField(Tags.DAEX, daex)

        msg += buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time)

        return msg
    }

    /** Largest reply accepted, in characters (replies seen are ~2 KB). */
    const val MAX_RESPONSE_CHARS = 64 * 1024

    /**
     * Reader check after each chunk: throws past [MAX_RESPONSE_CHARS], and walks the fields
     * ([isCompleteMessage]) only when the data ends with '!' or '?'. A reply ending otherwise
     * closes on silence.
     */
    fun isCompleteResponse(buf: CharSequence): Boolean {
        if (buf.length > MAX_RESPONSE_CHARS) {
            throw NapsError.invalidResponse("Response larger than $MAX_RESPONSE_CHARS characters")
        }
        val last = buf.lastOrNull() ?: return false
        return (last == TERMINATOR_V2 || last == TERMINATOR_V1) && isCompleteMessage(buf)
    }

    /**
     * True once [buf] holds a complete message: walking the fields by their length lands on a
     * '!'/'?' terminator. A terminator inside a value (receipt lines end with '?') is data, so a
     * read that happens to stop right after one is not taken as the end. If the data is not TLV
     * (the Lane3600 writes 999 as the length of a longer receipt), a trailing terminator is
     * accepted as the end.
     */
    fun isCompleteMessage(buf: CharSequence): Boolean {
        var pos = 0
        while (pos < buf.length) {
            val c = buf[pos]
            if (c == TERMINATOR_V2 || c == TERMINATOR_V1) return true
            if (pos + 6 > buf.length) return false
            val header = buf.subSequence(pos, pos + 6)
            if (!header.all { it.isDigit() }) {
                val last = buf[buf.length - 1]
                return last == TERMINATOR_V2 || last == TERMINATOR_V1
            }
            pos += 6 + header.subSequence(3, 6).toString().toInt()
        }
        return false
    }

    /**
     * Parse TLV string into fields map
     */
    fun parse(rawTlvString: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()

        // Stop at an end-of-message terminator ('!' v2, '?' v1) found at a field
        // boundary, so any noise after it is ignored. Terminator characters inside
        // a length-prefixed value (e.g. the '?' ending the DP print lines, §5) are
        // data. Both are accepted so the parser is protocol-version agnostic.
        // SECURITY: card numbers are masked before any field is read
        val tlvString = maskFrame(rawTlvString)
        var index = 0

        while (index < tlvString.length) {
            val c = tlvString[index]
            if (c == TERMINATOR_V2 || c == TERMINATOR_V1) break

            // Need at least 6 chars for tag + length
            if (index + 6 > tlvString.length) break

            val tag = tlvString.substring(index, index + 3)
            val lengthStr = tlvString.substring(index + 3, index + 6)

            // Validate length is numeric
            if (!lengthStr.all { it.isDigit() }) break

            val length = lengthStr.toInt()

            // Check if we have enough data for the value
            if (index + 6 + length > tlvString.length) break

            var value = tlvString.substring(index + 6, index + 6 + length)

            // SECURITY: NCAR (007) in the standard masked form, e.g. 516794******3315
            if (tag == Tags.NCAR) {
                value = maskCardNumber(value.filterNot { it == ' ' || it == '-' })
            }

            fields[tag] = value
            index += 6 + length
        }

        return fields
    }

    /**
     * Mask card number to show only first 6 and last 4 digits
     * Example: 5167940123453315 -> 516794******3315
     */
    fun maskCardNumber(cardNumber: String): String {
        if (cardNumber.length < 10) return cardNumber

        val first6 = cardNumber.substring(0, 6)
        val last4 = cardNumber.substring(cardNumber.length - 4)
        val maskedMiddle = "*".repeat(cardNumber.length - 10)

        return "$first6$maskedMiddle$last4"
    }

    // A card-shaped run: digits that are adjacent or separated by exactly one space,
    // dash, dot, tab or non-breaking space. Slashes, colons, commas and repeated
    // separators end a run, so dates, times and amounts are never joined into one.
    private val CARD_SHAPED_RUN = Regex("\\d(?:[ \\-.\t\u00A0]?\\d)*")

    /**
     * Mask every card-shaped run of 13 or more digits in text (longer runs too: fail
     * closed), keeping the first 6 and last 4 digits of the run. Only digits are
     * replaced, so separators and the text length are unchanged.
     * Example: "5167 9400 0012 3315" -> "5167 94** **** 3315"
     */
    fun maskCardNumbersInText(text: String): String =
        CARD_SHAPED_RUN.replace(text) { m ->
            if (m.value.count { it.isDigit() } >= 13) maskDigits(m.value) else m.value
        }

    /** Replace every digit except the first 6 and last 4 with '*', keeping other characters. */
    private fun maskDigits(value: String): String {
        val total = value.count { it.isDigit() }
        if (total < 10) return value
        var seen = 0
        return buildString {
            for (c in value) {
                if (c.isDigit()) {
                    append(if (seen in 6 until total - 4) '*' else c)
                    seen++
                } else append(c)
            }
        }
    }

    /**
     * Mask every card number in a raw TLV frame without changing its length, so
     * the TLV lengths stay valid: NCAR (007) values, DP (010) print-line texts and
     * any card number elsewhere in a value or in trailing bytes. Transports apply
     * this to every reply as soon as it is read.
     */
    fun maskFrame(frame: String): String {
        val out = StringBuilder(frame.length)
        var index = 0
        while (index + 6 <= frame.length) {
            val c = frame[index]
            if (c == TERMINATOR_V2 || c == TERMINATOR_V1) break
            val lengthStr = frame.substring(index + 3, index + 6)
            if (!lengthStr.all { it.isDigit() }) break
            val length = lengthStr.toInt()
            if (index + 6 + length > frame.length) break

            val tag = frame.substring(index, index + 3)
            val value = frame.substring(index + 6, index + 6 + length)
            out.append(frame, index, index + 6)
            out.append(
                when (tag) {
                    Tags.NCAR -> maskDigits(value)
                    Tags.DP -> maskPrintBlock(value)
                    else -> maskCardNumbersInText(value)
                }
            )
            index += 6 + length
        }
        // Terminator, trailing bytes or an unparseable remainder
        out.append(maskCardNumbersInText(frame.substring(index)))
        return out.toString()
    }

    /** Mask card numbers in each DP line text (sub-tag 033), keeping the block's structure. */
    private fun maskPrintBlock(dp: String): String {
        val out = StringBuilder(dp.length)
        var index = 0
        while (index < dp.length) {
            val c = dp[index]
            if (c == '*' || c == '?') { out.append(c); index++; continue }
            if (index + 6 > dp.length) break
            val lengthStr = dp.substring(index + 3, index + 6)
            if (!lengthStr.all { it.isDigit() }) break
            val length = lengthStr.toInt()
            if (index + 6 + length > dp.length) break
            val value = dp.substring(index + 6, index + 6 + length)
            out.append(dp, index, index + 6)
            out.append(if (dp.startsWith(ReceiptTags.CONTENT, index)) maskCardNumbersInText(value) else value)
            index += 6 + length
        }
        out.append(maskCardNumbersInText(dp.substring(index)))
        return out.toString()
    }

    /**
     * Get current date and time for NAPS Pay format
     */
    private fun getCurrentDateTime(): DateTime {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("ddMMyyyy", Locale.US)
        val timeFormat = SimpleDateFormat("HHmmss", Locale.US)

        return DateTime(
            date = dateFormat.format(calendar.time),
            time = timeFormat.format(calendar.time)
        )
    }

    /**
     * Data class for date/time
     */
    private data class DateTime(
        val date: String,
        val time: String
    )

    /**
     * Get tag name for debugging
     */
    fun getTagName(tag: String): String = when (tag) {
        Tags.TM -> "Message Type"
        Tags.MT -> "Amount"
        Tags.NCAI -> "Terminal Number"
        Tags.NS -> "Sequence Number"
        Tags.NSA -> "Cancellation Sequence"
        Tags.NHC -> "Hostess Number"
        Tags.NCAR -> "Card Number"
        Tags.STAN -> "STAN"
        Tags.NA -> "Auth Number"
        Tags.DP -> "Printable Data"
        Tags.CB -> "Barcode"
        Tags.DE -> "Currency"
        Tags.CR -> "Response Code"
        Tags.DA -> "Date"
        Tags.HE -> "Time"
        Tags.NPRT -> "Cardholder Name"
        Tags.DAEX -> "Expiration Date"
        Tags.DATR -> "Transaction Date"
        Tags.HETR -> "Transaction Time"
        Tags.TIDE -> "Ticket Type"
        Tags.TYPA -> "Transaction Type"
        Tags.RE -> "Receipt Data"
        Tags.RECB -> "Receipt Copy"
        Tags.REQU -> "Request Type"
        Tags.RESE -> "Response Message"
        Tags.RECO -> "Receipt Confirmation"
        Tags.RERA -> "Response Reason"
        Tags.MDLC -> "Model Code"
        Tags.EM -> "Entry Mode"
        else -> "Unknown Tag $tag"
    }
}
