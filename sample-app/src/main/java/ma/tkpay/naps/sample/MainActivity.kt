package ma.tkpay.naps.sample

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ma.tkpay.naps.NapsPayClient
import ma.tkpay.naps.app2app.App2AppClient
import ma.tkpay.naps.app2app.App2AppResult
import ma.tkpay.naps.config.NapsConfig
import ma.tkpay.naps.models.NapsError
import ma.tkpay.naps.models.PaymentRequest
import ma.tkpay.naps.sample.databinding.ActivityMainBinding

/**
 * Sample app demonstrating both integration modes of the NAPS Pay SDK:
 *
 * MODE 1 — TCP/M2M (NapsPayClient):
 *   Direct socket connection to NAPS Pay on port 4444.
 *   Best for custom terminals or when NAPS Pay is on a different device.
 *
 * MODE 2 — App2App (App2AppClient):
 *   Launches NAPS Pay via Android Intent. NAPS Pay handles the card interaction
 *   and returns the result to this app.
 *   - printReceipt = true  → NAPS Pay prints, SDK returns transaction data only.
 *   - printReceipt = false → NAPS Pay does NOT print. SDK builds and returns
 *     merchant + customer receipts. Developer decides: print via Sunmi, store,
 *     email, etc.
 *   Best for Sunmi POS devices where both apps run side-by-side.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // TCP mode client (created per-payment)
    private var tcpClient: NapsPayClient? = null

    // App2App client — must be registered in onCreate()
    private lateinit var app2AppClient: App2AppClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register App2App client — MUST be in onCreate before activity starts
        app2AppClient = App2AppClient.register(
            activity = this,
            onResult = { result -> handleApp2AppResult(result) }
        )

        setupListeners()
        updateModeVisibility()
    }

    private fun setupListeners() {
        binding.modeTcpRadio.setOnClickListener { updateModeVisibility() }
        binding.modeApp2AppRadio.setOnClickListener { updateModeVisibility() }

        binding.testConnectionButton.setOnClickListener { testTcpConnection() }
        binding.processPaymentButton.setOnClickListener { processPayment() }
    }

    private fun updateModeVisibility() {
        val isTcp = binding.modeTcpRadio.isChecked
        binding.hostLayout.visibility = if (isTcp) View.VISIBLE else View.GONE
        binding.testConnectionButton.visibility = if (isTcp) View.VISIBLE else View.GONE
        binding.printReceiptLayout.visibility = if (isTcp) View.GONE else View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // TCP Mode
    // -------------------------------------------------------------------------

    private fun testTcpConnection() {
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
                tcpClient = NapsPayClient(config)
                val connected = tcpClient!!.testConnection()

                binding.resultTextView.text = if (connected) {
                    "✅ Connection successful!\nReady to process payments."
                } else {
                    "❌ Connection failed\n\nCheck:\n- Terminal IP\n- Terminal on same network\n- NAPS Pay app running"
                }
            } catch (e: Exception) {
                binding.resultTextView.text = "❌ Error: ${e.message}"
            } finally {
                binding.testConnectionButton.isEnabled = true
            }
        }
    }

    private fun processPayment() {
        val amountStr = binding.amountEditText.text.toString()
        val amount = amountStr.toDoubleOrNull()

        if (amount == null || amount <= 0) {
            Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
            return
        }

        if (binding.modeTcpRadio.isChecked) {
            processTcpPayment(amount)
        } else {
            processApp2AppPayment(amount)
        }
    }

    private fun processTcpPayment(amount: Double) {
        val host = binding.hostEditText.text.toString()
        if (host.isBlank()) {
            Toast.makeText(this, "Please enter terminal IP", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.resultTextView.text = "Processing $amount MAD via TCP...\nPlease tap card on terminal.\n"
                binding.processPaymentButton.isEnabled = false

                val config = NapsConfig(host = host)
                val client = NapsPayClient(config)
                val result = client.processPayment(
                    PaymentRequest(amount = amount, registerId = "01", cashierId = "00001")
                )

                if (result.isApproved()) {
                    displayTcpSuccess(result)
                    Toast.makeText(this@MainActivity, "Payment Approved!", Toast.LENGTH_LONG).show()
                } else {
                    binding.resultTextView.text = "❌ PAYMENT FAILED\nCode: ${result.responseCode}\n${result.error}"
                    Toast.makeText(this@MainActivity, "Payment Failed", Toast.LENGTH_LONG).show()
                }
            } catch (e: NapsError) {
                binding.resultTextView.text = "❌ NapsError: ${e.message}\nCode: ${e.code}"
            } catch (e: Exception) {
                binding.resultTextView.text = "❌ Error: ${e.message}"
            } finally {
                binding.processPaymentButton.isEnabled = true
            }
        }
    }

    // -------------------------------------------------------------------------
    // App2App Mode
    // -------------------------------------------------------------------------

    private fun processApp2AppPayment(amountMad: Double) {
        if (!app2AppClient.isNapsPayInstalled()) {
            binding.resultTextView.text = "❌ NAPS Pay not installed on this device.\n\nInstall com.m2mgroup.napspay first."
            return
        }

        val amountCentimes = (amountMad * 100).toLong()
        val printReceipt = binding.printReceiptSwitch.isChecked
        val orderId = System.currentTimeMillis().toString().takeLast(8).padStart(8, '0')

        binding.resultTextView.text = buildString {
            append("Launching NAPS Pay...\n\n")
            append("Amount: $amountMad MAD\n")
            append("Order ID: $orderId\n")
            append("Print receipt: $printReceipt\n\n")
            if (!printReceipt) append("📄 SDK will build merchant + customer receipts.\n")
        }
        binding.processPaymentButton.isEnabled = false

        // Launch NAPS Pay — result delivered to handleApp2AppResult()
        app2AppClient.pay(
            orderId = orderId,
            amountCentimes = amountCentimes,
            printReceipt = printReceipt
        )
    }

    /**
     * Called by App2AppClient when NAPS Pay returns a result.
     * Runs on the main thread.
     */
    private fun handleApp2AppResult(result: App2AppResult) {
        binding.processPaymentButton.isEnabled = true

        if (result.isApproved()) {
            displayApp2AppSuccess(result)
            Toast.makeText(this, "Payment Approved!", Toast.LENGTH_LONG).show()
        } else if (result.isCancelled) {
            binding.resultTextView.text = "⚠️ CANCELLED\n\nUser cancelled the payment in NAPS Pay."
            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        } else {
            binding.resultTextView.text = buildString {
                append("❌ PAYMENT FAILED\n\n")
                append("Code: ${result.responseCode}\n")
                result.responseMessage?.let { append("Message: $it\n") }
                append("Error: ${result.errorType.defaultMessage}\n")
            }
            Toast.makeText(this, "Payment Failed", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // Display helpers
    // -------------------------------------------------------------------------

    private fun displayTcpSuccess(result: ma.tkpay.naps.models.PaymentResult) {
        binding.resultTextView.text = buildString {
            append("✅ PAYMENT APPROVED (TCP)\n\n")
            append("STAN: ${result.stan}\n")
            append("Auth: ${result.authNumber}\n")
            append("Card: ${result.getFormattedCardNumber()}\n")
            append("Expiry: ${result.getFormattedExpiry()}\n")
            result.entryMode?.let { append("Entry: $it\n") }
            result.transactionDate?.let { append("Date: $it\n") }
            result.transactionTime?.let { append("Time: $it\n") }
            append("\n")
            result.merchantReceipt?.let {
                append("─── MERCHANT RECEIPT ───\n${it.toPlainText()}\n\n")
            }
            result.customerReceipt?.let {
                append("─── CUSTOMER RECEIPT ───\n${it.toPlainText()}\n")
            }
        }
    }

    private fun displayApp2AppSuccess(result: App2AppResult) {
        binding.resultTextView.text = buildString {
            append("✅ PAYMENT APPROVED (App2App)\n\n")
            append("STAN: ${result.stan}\n")
            append("RRN: ${result.rrn}\n")
            append("Approval: ${result.approvalCode}\n")
            append("Receipt #: ${result.receiptNumber}\n")
            append("Card: ${result.getFormattedCardNumber()}\n")
            result.cardScheme?.let { append("Scheme: $it\n") }
            result.terminalId?.let { append("Terminal: $it\n") }
            result.merchantName?.let { append("Merchant: $it\n") }
            result.transactionDate?.let { append("Date: $it\n") }
            result.transactionTime?.let { append("Time: $it\n") }

            if (result.merchantReceipt != null || result.customerReceipt != null) {
                append("\n📄 Receipts built by SDK (printReceipt=false)\n")
                append("Developer can print, store, or email these.\n\n")

                result.merchantReceipt?.let {
                    append("─── MERCHANT RECEIPT ───\n")
                    append(it.toPlainText())
                    append("\n\n")
                    // Example: to print via Sunmi printer, pass it.lines to SunmiPrinterService
                }
                result.customerReceipt?.let {
                    append("─── CUSTOMER RECEIPT ───\n")
                    append(it.toPlainText())
                    append("\n")
                }
            } else {
                append("\n(Receipts printed by NAPS Pay)")
            }
        }
    }
}
