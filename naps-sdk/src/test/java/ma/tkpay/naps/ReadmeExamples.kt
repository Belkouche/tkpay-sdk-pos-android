package ma.tkpay.naps

import android.content.Context
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.config.TransportType
import ma.tkpay.naps.models.Alignment
import ma.tkpay.naps.models.ErrorCode
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.models.Receipt
import ma.tkpay.naps.protocol.TlvProtocol

/**
 * The code samples of README.md, kept here so the build compiles them against the
 * real public API. When editing a README snippet, edit it here too (and vice versa).
 * Run against the emulator by ReadmeExamplesTest.
 */
@Suppress("unused", "UNUSED_VARIABLE")
internal object ReadmeExamples {

    fun printReceipt(receipt: Receipt) = println(receipt.toPlainText())

    // README: "Process a payment"
    suspend fun payment(client: NapsPayClient): Triple<String, String, Long>? {
        val amount = 150.00
        try {
            val result = client.processPayment(
                PaymentRequest(amount = amount, registerId = "01", cashierId = "00001")
            )
            if (result.isApproved()) {
                // Keep these three values: a void needs them
                val stan = result.stan!!
                val sequence = result.sequence!!
                val amountCentimes = TlvProtocol.toCentimes(amount)   // 15000
                result.merchantReceipt?.let { printReceipt(it) }
                result.customerReceipt?.let { printReceipt(it) }
                return Triple(stan, sequence, amountCentimes)
            } else {
                // Declined by the card network, the issuer or the terminal
                println("Declined [${result.responseCode}]: ${result.error}")
                result.merchantReceipt?.let { printReceipt(it) }   // decline ticket, if any
            }
        } catch (e: NapsError) {
            // No usable answer from the terminal
            when (e.code) {
                ErrorCode.CONNECTION_FAILED -> println("Terminal unreachable: ${e.message}")
                ErrorCode.TIMEOUT -> println("Terminal did not answer in time")
                else -> println("Protocol error: ${e.message}")
            }
        }
        return null
    }

    // README: "Void a payment"
    suspend fun void(client: NapsPayClient, stan: String, sequence: String, amountCentimes: Long) {
        val void = client.cancelPayment(
            stan = stan,
            registerId = "01",
            cashierId = "00001",
            sequence = sequence,
            amountCentimes = amountCentimes
        )
        when {
            void.success -> {
                println("Voided (CR ${void.responseCode}), STAN ${void.stan}")
                void.merchantReceipt?.let { printReceipt(it) }
            }
            void.pending -> {
                // Outcome unknown: the reversal may or may not have run.
                // Do NOT mark the sale as voided. Reprint the receipt for this STAN
                // and check it before doing anything else.
                println("Void unconfirmed (CR '${void.responseCode}'), checking STAN ${void.stan}")
                val dup = client.printDuplicate("01", "00001", stan = void.stan)
                dup.merchantReceipt?.let { printReceipt(it) }
            }
            else -> println("Void refused [${void.responseCode}]: ${void.error}")
        }
    }

    // README: "Other operations"
    suspend fun otherOperations(client: NapsPayClient) {
        val reachable: Boolean = client.testConnection()

        val net = client.networkTest("01", "00001")
        println(if (net.success) "Network OK (${net.rttMs} ms)" else "Network test failed: ${net.error}")

        val ref = client.referencing("01", "00001")                      // merchant set "01"
        val recharge = client.referencing("01", "00001", TlvProtocol.Tide.RECHARGE)
        if (ref.success) ref.receipt?.let { printReceipt(it) }

        val dup = client.printDuplicate("01", "00001")                   // last transaction
        dup.merchantReceipt?.let { printReceipt(it) }

        val totals = client.totals("01", "00001")
        totals.receipt?.let { printReceipt(it) }

        val settlement = client.settlement("01", "00001")
        println(if (settlement.success) "Settled ${settlement.date} ${settlement.time}" else "Settlement failed: ${settlement.error}")

        val reset = client.resetPinPad("01", "00001")
        println(if (reset.success) "PIN pad reset" else "Reset failed: ${reset.error}")
    }

    // README: "Receipts"
    fun receipts(receipt: Receipt) {
        println(receipt.toPlainText())        // one line per print line
        println(receipt.toFormattedText())    // e.g. "[B][C]TKpay" = bold, centred

        for (line in receipt.lines) {
            val align = when (line.alignment) {
                Alignment.LEFT -> "L"
                Alignment.CENTER -> "C"
                Alignment.RIGHT -> "R"
            }
            println("$align ${if (line.bold) "B" else "-"} ${line.text}")
        }
    }

    // README: "Response codes"
    fun responseCodes() {
        TlvProtocol.describeResponse("116")   // "Insufficient funds"
        TlvProtocol.isApproved("000")         // true (approval set: 000, 001, 003, 007)
        TlvProtocol.RESPONSE_CODES            // the full table, Map<String, String>
    }

    // README: "Configuration"
    fun configuration(context: Context) {
        val tcp = NapsConfig(
            host = "192.168.1.50",       // terminal IP on the shop Wi-Fi
            port = 4444,                 // default
            timeout = 120_000,           // ms, connect + Phase 1 (default 2 min)
            confirmationTimeout = 40_000 // ms, TM 002 / TM 004 replies (default 40 s)
        )
        val client = NapsPayClient(tcp)

        // Ingenico over USB: needs a Context for the USB stack
        val usbClient = NapsPayClient(NapsConfig(transport = TransportType.USB_SERIAL), context)
    }
}
