package ma.tkpay.naps.protocol

import ma.tkpay.naps.models.NapsError
import java.text.SimpleDateFormat
import java.util.*

/**
 * TLV Protocol implementation for NAPS Pay M2M (v5.0.3)
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
        const val NCAR = "007"    // Card Number (masked) (16 chars)
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
        const val DUPLICATE_REQUEST = "008"
        const val DUPLICATE_RESPONSE = "108"
        const val NETWORK_TEST_REQUEST = "009"
        const val NETWORK_TEST_RESPONSE = "109"
        const val SETTLEMENT_REQUEST = "010"
        const val SETTLEMENT_RESPONSE = "110"
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
        val amountMinor = (amount * 100).toInt().toString()
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
     * Build cancellation request TLV (TM=003)
     */
    fun buildCancellationRequest(
        stan: String,
        ncai: String,
        sequence: String
    ): String {
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.CANCELLATION_REQUEST) +
               buildField(Tags.STAN, stan) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.NSA, sequence) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time)
    }

    /**
     * Build duplicate receipt request TLV (TM=008)
     */
    fun buildDuplicateRequest(ncai: String, stan: String? = null): String {
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.DUPLICATE_REQUEST) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time) +
               (if (stan != null) buildField(Tags.STAN, stan) else "")
    }

    /**
     * Build network test request TLV (TM=009)
     */
    fun buildNetworkTestRequest(ncai: String): String {
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.NETWORK_TEST_REQUEST) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time)
    }

    /**
     * Build reset PinPAD request TLV (TM=012)
     */
    fun buildResetRequest(ncai: String): String {
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.RESET_REQUEST) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time)
    }

    /**
     * Build referencing request TLV (TM=013)
     */
    fun buildReferencingRequest(ncai: String): String {
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.REFERENCING_REQUEST) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time)
    }

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
     * Build confirmation request TLV
     *
     * Sends current date/time as DA (014) and HE (015),
     * plus transaction date/time as DATR (018) and HETR (019).
     */
    fun buildConfirmationRequest(
        stan: String,
        ncai: String,
        sequence: String
    ): String {
        val dateTime = getCurrentDateTime()

        return buildField(Tags.TM, MessageTypes.CONFIRMATION_REQUEST) +
               buildField(Tags.STAN, stan) +
               buildField(Tags.NCAI, ncai) +
               buildField(Tags.NS, sequence) +
               buildField(Tags.DA, dateTime.date) +
               buildField(Tags.HE, dateTime.time) +
               buildField(Tags.DATR, dateTime.date) +
               buildField(Tags.HETR, dateTime.time)
    }

    /**
     * Parse TLV string into fields map
     */
    fun parse(tlvString: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        var index = 0

        while (index < tlvString.length) {
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

            // SECURITY: Immediately mask PAN in tag 007 (NCAR)
            if (tag == Tags.NCAR) {
                value = maskCardNumber(value)
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

    /**
     * Mask any 16-digit card numbers in text
     */
    fun maskCardNumbersInText(text: String): String {
        val panPattern = Regex("\\b\\d{16}\\b")
        return panPattern.replace(text) { matchResult ->
            maskCardNumber(matchResult.value)
        }
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
