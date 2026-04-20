package ma.tkpay.naps.sample

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ma.tkpay.naps.NapsPayClient
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.sample.databinding.ActivityMainBinding

/**
 * Sample app demonstrating NAPS Pay SDK usage
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var napsClient: NapsPayClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.testConnectionButton.setOnClickListener {
            testConnection()
        }

        binding.processPaymentButton.setOnClickListener {
            processPayment()
        }
    }

    private fun testConnection() {
        val host = binding.hostEditText.text.toString()

        if (host.isBlank()) {
            Toast.makeText(this, "Please enter terminal IP", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.resultTextView.text = "Testing connection to $host:4444...\n"
                binding.testConnectionButton.isEnabled = false

                val config = NapsConfig(host = host)
                napsClient = NapsPayClient(config)

                val connected = napsClient!!.testConnection()

                if (connected) {
                    binding.resultTextView.text = "✅ Connection successful!\n\nReady to process payments."
                    Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.resultTextView.text = "❌ Connection failed\n\nPlease check:\n" +
                            "- Terminal IP address\n" +
                            "- Terminal is on and connected to network\n" +
                            "- NAPS Pay app is running on terminal"
                }

            } catch (e: NapsError) {
                binding.resultTextView.text = "❌ Error: ${e.message}\n\n${e.code}"
            } catch (e: Exception) {
                binding.resultTextView.text = "❌ Unexpected error: ${e.message}"
            } finally {
                binding.testConnectionButton.isEnabled = true
            }
        }
    }

    private fun processPayment() {
        val host = binding.hostEditText.text.toString()
        val amountStr = binding.amountEditText.text.toString()

        if (host.isBlank()) {
            Toast.makeText(this, "Please enter terminal IP", Toast.LENGTH_SHORT).show()
            return
        }

        if (amountStr.isBlank()) {
            Toast.makeText(this, "Please enter amount", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.resultTextView.text = "Processing payment of $amount MAD...\n" +
                        "Please tap card on terminal.\n"
                binding.processPaymentButton.isEnabled = false

                // Create config and client
                val config = NapsConfig(host = host)
                val client = NapsPayClient(config)

                // Create payment request
                val request = PaymentRequest(
                    amount = amount,
                    registerId = "01",
                    cashierId = "00001"
                )

                // Process payment
                val result = client.processPayment(request)

                // Display result
                if (result.isApproved()) {
                    displaySuccessResult(result)
                    Toast.makeText(this@MainActivity, "Payment Approved!", Toast.LENGTH_LONG).show()
                } else {
                    displayFailedResult(result)
                    Toast.makeText(this@MainActivity, "Payment Failed", Toast.LENGTH_LONG).show()
                }

            } catch (e: NapsError) {
                binding.resultTextView.text = buildString {
                    append("❌ Payment Error\n\n")
                    append("Code: ${e.code}\n")
                    append("Message: ${e.message}\n\n")

                    when (e.code) {
                        ma.tkpay.naps.models.ErrorCode.CONNECTION_FAILED -> {
                            append("Troubleshooting:\n")
                            append("- Check terminal IP address\n")
                            append("- Ensure terminal is on same network\n")
                            append("- Verify NAPS Pay app is running\n")
                        }
                        ma.tkpay.naps.models.ErrorCode.TIMEOUT -> {
                            append("Troubleshooting:\n")
                            append("- Ensure card was tapped on terminal\n")
                            append("- Check terminal is responding\n")
                        }
                        else -> {}
                    }
                }
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.resultTextView.text = "❌ Unexpected error: ${e.message}\n\n${e.stackTraceToString()}"
            } finally {
                binding.processPaymentButton.isEnabled = true
            }
        }
    }

    private fun displaySuccessResult(result: ma.tkpay.naps.models.PaymentResult) {
        binding.resultTextView.text = buildString {
            append("✅ PAYMENT APPROVED\n\n")
            append("═══════════════════════════\n\n")

            append("Transaction Details:\n")
            append("─────────────────\n")
            append("STAN: ${result.stan}\n")
            append("Auth: ${result.authNumber}\n")
            append("Response: ${result.responseCode}\n\n")

            append("Card Details:\n")
            append("─────────────────\n")
            append("Card: ${result.getFormattedCardNumber()}\n")
            append("Expiry: ${result.getFormattedExpiry()}\n")
            result.cardholderName?.let { append("Name: $it\n") }
            result.entryMode?.let { append("Entry: $it\n") }
            append("\n")

            append("Transaction Info:\n")
            append("─────────────────\n")
            result.transactionDate?.let { append("Date: $it\n") }
            result.transactionTime?.let { append("Time: $it\n") }
            result.sequence?.let { append("Seq: $it\n") }
            append("\n")

            // Display merchant receipt
            result.merchantReceipt?.let { receipt ->
                append("═══════════════════════════\n")
                append("MERCHANT RECEIPT\n")
                append("═══════════════════════════\n\n")
                append(receipt.toPlainText())
                append("\n\n")
            }

            // Display customer receipt
            result.customerReceipt?.let { receipt ->
                append("═══════════════════════════\n")
                append("CUSTOMER RECEIPT\n")
                append("═══════════════════════════\n\n")
                append(receipt.toPlainText())
                append("\n")
            }
        }
    }

    private fun displayFailedResult(result: ma.tkpay.naps.models.PaymentResult) {
        binding.resultTextView.text = buildString {
            append("❌ PAYMENT FAILED\n\n")
            append("═══════════════════════════\n\n")

            append("Error Details:\n")
            append("─────────────────\n")
            append("Code: ${result.responseCode}\n")
            append("Message: ${result.error ?: "Unknown error"}\n\n")

            result.stan?.let {
                append("STAN: $it\n\n")
            }

            when (result.responseCode) {
                "909" -> append("Terminal or server is down.\nPlease check the terminal status.\n")
                "302" -> append("Transaction not found.\nPlease try again.\n")
                "482" -> append("Transaction already cancelled.\n")
                "480" -> append("Transaction was cancelled.\n")
                else -> append("Please try again or contact support.\n")
            }
        }
    }
}
