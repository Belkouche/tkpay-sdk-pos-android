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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastStan: String? = null
    private var lastSequence: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupListeners()
    }

    private fun setupListeners() {
        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.networkTestButton.setOnClickListener { networkTest() }
        binding.referencingButton.setOnClickListener { referencing() }
        binding.processPaymentButton.setOnClickListener { processPayment() }
        binding.cancelButton.setOnClickListener { cancelPayment() }
        binding.duplicateButton.setOnClickListener { printDuplicate() }
        binding.settlementButton.setOnClickListener { settlement() }
        binding.resetButton.setOnClickListener { resetPinPad() }
    }

    private fun getClient(): NapsPayClient {
        val host = binding.hostEditText.text.toString().trim()
        require(host.isNotBlank()) { "Enter the terminal IP address" }
        return NapsPayClient(NapsConfig(host = host))
    }

    // ── TCP socket test ────────────────────────────────────────────────────

    private fun testConnection() {
        withClient("Testing TCP connection…") { client ->
            val ok = client.testConnection()
            if (ok) "✅ TCP connection OK — terminal reachable"
            else "❌ Cannot connect — check IP and that NAPS Pay is running"
        }
    }

    // ── Protocol-level health check (TM=009) ──────────────────────────────

    private fun networkTest() {
        withClient("Sending network test (TM=009)…") { client ->
            val result = client.networkTest("01", "00001")
            if (result.success)
                "✅ Network test OK — RTT ${result.rttMs}ms"
            else
                "❌ Network test failed [${result.responseCode}]: ${result.error}"
        }
    }

    // ── Referencing (TM=013) ───────────────────────────────────────────────

    private fun referencing() {
        withClient("Running referencing (TM=013)…") { client ->
            val result = client.referencing("01", "00001")
            if (result.success) {
                buildString {
                    append("✅ Referencing OK\n\n")
                    result.receipt?.let { append(it.toPlainText()) }
                }
            } else {
                "❌ Referencing failed [${result.responseCode}]: ${result.error}"
            }
        }
    }

    // ── Payment (TM=001/002) ───────────────────────────────────────────────

    private fun processPayment() {
        val amount = binding.amountEditText.text.toString().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        withClient("Processing $amount MAD…\nPlease tap card on terminal.") { client ->
            val result = client.processPayment(
                PaymentRequest(amount = amount, registerId = "01", cashierId = "00001")
            )

            if (result.isApproved()) {
                lastStan = result.stan
                lastSequence = result.sequence
                buildString {
                    append("✅ APPROVED\n\n")
                    append("STAN:   ${result.stan}\n")
                    append("Auth:   ${result.authNumber}\n")
                    append("Card:   ${result.getFormattedCardNumber()}\n")
                    append("Expiry: ${result.getFormattedExpiry()}\n")
                    result.entryMode?.let { append("Mode:   $it\n") }
                    append("\n")
                    result.merchantReceipt?.let { append("── Merchant ──\n${it.toPlainText()}\n\n") }
                    result.customerReceipt?.let { append("── Customer ──\n${it.toPlainText()}\n") }
                }
            } else {
                "❌ DECLINED [${result.responseCode}]: ${result.error}"
            }
        }
    }

    // ── Cancel (TM=003) ───────────────────────────────────────────────────

    private fun cancelPayment() {
        val stan = lastStan
        val seq = lastSequence
        if (stan == null || seq == null) {
            binding.resultTextView.text = "No previous transaction to cancel."
            return
        }

        withClient("Cancelling STAN=$stan…") { client ->
            val result = client.cancelPayment(stan, "01", "00001", seq)
            if (result.success) {
                lastStan = null; lastSequence = null
                "✅ Cancelled — STAN: ${result.stan}"
            } else {
                "❌ Cancel failed [${result.responseCode}]: ${result.error}"
            }
        }
    }

    // ── Duplicate receipt (TM=008) ────────────────────────────────────────

    private fun printDuplicate() {
        withClient("Requesting duplicate receipt (TM=008)…") { client ->
            val result = client.printDuplicate("01", "00001", stan = lastStan)
            if (result.success) {
                buildString {
                    append("✅ Duplicate OK\n\n")
                    result.merchantReceipt?.let { append(it.toPlainText()) }
                }
            } else {
                "❌ Duplicate failed [${result.responseCode}]: ${result.error}"
            }
        }
    }

    // ── Settlement (TM=010) ───────────────────────────────────────────────

    private fun settlement() {
        withClient("Running settlement (TM=010)…") { client ->
            val result = client.settlement("01", "00001")
            if (result.success)
                "✅ Settlement OK — ${result.date} ${result.time}"
            else
                "❌ Settlement failed [${result.responseCode}]: ${result.error}"
        }
    }

    // ── Reset (TM=012) ────────────────────────────────────────────────────

    private fun resetPinPad() {
        withClient("Resetting terminal (TM=012)…") { client ->
            val result = client.resetPinPad("01", "00001")
            if (result.success) "✅ Terminal reset — ready"
            else "❌ Reset failed [${result.responseCode}]: ${result.error}"
        }
    }

    // ── Helper: run a suspend block, show status + result ─────────────────

    private fun withClient(status: String, block: suspend (NapsPayClient) -> String) {
        val client = try {
            getClient()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            return
        }

        binding.resultTextView.text = status
        setButtonsEnabled(false)

        lifecycleScope.launch {
            binding.resultTextView.text = try {
                block(client)
            } catch (e: NapsError) {
                "❌ NapsError [${e.code}]: ${e.message}"
            } catch (e: Exception) {
                "❌ ${e.message}"
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(
            binding.testConnectionButton,
            binding.networkTestButton,
            binding.referencingButton,
            binding.processPaymentButton,
            binding.cancelButton,
            binding.duplicateButton,
            binding.settlementButton,
            binding.resetButton,
        ).forEach { it.isEnabled = enabled }
    }
}
