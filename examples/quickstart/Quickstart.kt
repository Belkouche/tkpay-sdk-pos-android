package ma.tkpay.naps.examples

import kotlinx.coroutines.runBlocking
import ma.tkpay.naps.NapsPayClient
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.PaymentRequest

fun main() = runBlocking {
    val port = System.getenv("NAPS_PORT")?.toInt() ?: 4444
    val client = NapsPayClient(NapsConfig(host = "127.0.0.1", port = port))

    val result = client.processPayment(
        PaymentRequest(amount = 10.00, registerId = "01", cashierId = "00001")
    )

    if (result.isApproved()) {
        println("Approved — STAN ${result.stan}, card ${result.getFormattedCardNumber()}")
        println(result.customerReceipt?.toPlainText())
    } else {
        println("Declined [${result.responseCode}]: ${result.error}")
    }
}
