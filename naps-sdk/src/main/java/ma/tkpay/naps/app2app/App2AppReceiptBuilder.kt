package ma.tkpay.naps.app2app

import ma.tkpay.naps.models.Alignment
import ma.tkpay.naps.models.Receipt
import ma.tkpay.naps.models.ReceiptLine
import ma.tkpay.naps.models.ReceiptType

/**
 * Builds merchant and customer thermal receipts from App2App response data.
 *
 * Used only when printReceipt = false — NAPS Pay does not print in that mode,
 * so the SDK constructs both copies from the response extras.
 *
 * Format: 32 chars wide (58mm thermal printer standard).
 */
internal object App2AppReceiptBuilder {

    private const val WIDTH = 32

    /**
     * Build both receipt copies from App2App response data.
     *
     * @param amount         Amount in MAD as a Double (e.g. 150.00).
     * @param maskedCard     Masked card number from NAPS Pay (first 6 + last 4).
     * @param cardScheme     Card network label (e.g. "VISA").
     * @param approvalCode   Authorization/approval code.
     * @param stan           System Trace Audit Number.
     * @param rrn            Retrieval Reference Number.
     * @param receiptNumber  Receipt number.
     * @param merchantName   Merchant name.
     * @param merchantCity   Merchant city.
     * @param terminalId     Terminal ID.
     * @param transactionDate Date string (DDMMYYYY).
     * @param transactionTime Time string (HHMMSS).
     */
    fun build(
        amount: Long,            // centimes
        maskedCard: String?,
        cardScheme: String?,
        approvalCode: String?,
        stan: String?,
        rrn: String?,
        receiptNumber: String?,
        merchantName: String?,
        merchantCity: String?,
        terminalId: String?,
        transactionDate: String?,
        transactionTime: String?
    ): Pair<Receipt, Receipt> {
        val formattedAmount = formatAmount(amount)
        val formattedDate = formatDate(transactionDate)
        val formattedTime = formatTime(transactionTime)

        val sharedLines = buildSharedLines(
            formattedAmount = formattedAmount,
            maskedCard = maskedCard,
            cardScheme = cardScheme,
            approvalCode = approvalCode,
            stan = stan,
            rrn = rrn,
            receiptNumber = receiptNumber,
            merchantName = merchantName,
            merchantCity = merchantCity,
            terminalId = terminalId,
            date = formattedDate,
            time = formattedTime
        )

        val merchantLines = sharedLines + listOf(
            separator(),
            center("COPIE COMMERCANT", bold = true),
            separator(),
            center("Signature: ___________________"),
            blank()
        )

        val customerLines = sharedLines + listOf(
            separator(),
            center("COPIE CLIENT", bold = true),
            separator(),
            center("Conservez ce ticket"),
            blank()
        )

        return Receipt(merchantLines, ReceiptType.MERCHANT) to
                Receipt(customerLines, ReceiptType.CUSTOMER)
    }

    // -------------------------------------------------------------------------

    private fun buildSharedLines(
        formattedAmount: String,
        maskedCard: String?,
        cardScheme: String?,
        approvalCode: String?,
        stan: String?,
        rrn: String?,
        receiptNumber: String?,
        merchantName: String?,
        merchantCity: String?,
        terminalId: String?,
        date: String,
        time: String
    ): List<ReceiptLine> = buildList {
        add(separator())
        add(center("TKPAY", bold = true))
        add(center("Powered by NAPS"))
        add(separator())

        if (!merchantName.isNullOrBlank()) add(center(merchantName.uppercase(), bold = true))
        if (!merchantCity.isNullOrBlank()) add(center(merchantCity))
        if (!terminalId.isNullOrBlank()) add(row("Terminal", terminalId))

        add(separator())

        add(row("Date", "$date  $time"))
        if (!receiptNumber.isNullOrBlank()) add(row("Ticket N°", receiptNumber))

        add(separator())

        add(center("ACHAT", bold = true))
        add(center(formattedAmount, bold = true))

        add(separator())

        if (!maskedCard.isNullOrBlank()) add(row("Carte", maskedCard))
        if (!cardScheme.isNullOrBlank()) add(row("Réseau", cardScheme.uppercase()))
        add(row("Mode", "Puce / Sans contact"))

        add(separator())

        if (!approvalCode.isNullOrBlank()) add(row("Code autorisation", approvalCode))
        if (!stan.isNullOrBlank()) add(row("STAN", stan))
        if (!rrn.isNullOrBlank()) add(row("RRN", rrn))

        add(separator())
        add(center("APPROUVE", bold = true))
        add(separator())
    }

    // ---- Helpers ------------------------------------------------------------

    private fun separator() = ReceiptLine(
        lineNumber = "",
        text = "=".repeat(WIDTH),
        bold = false,
        alignment = Alignment.LEFT
    )

    private fun blank() = ReceiptLine(
        lineNumber = "",
        text = "",
        bold = false,
        alignment = Alignment.LEFT
    )

    private fun center(text: String, bold: Boolean = false) = ReceiptLine(
        lineNumber = "",
        text = text,
        bold = bold,
        alignment = Alignment.CENTER
    )

    /** Two-column row: label left, value right, padded to WIDTH. */
    private fun row(label: String, value: String): ReceiptLine {
        val maxLabelLen = WIDTH - value.length - 1
        val truncated = if (label.length > maxLabelLen) label.take(maxLabelLen) else label
        val padding = WIDTH - truncated.length - value.length
        val text = truncated + " ".repeat(maxOf(1, padding)) + value
        return ReceiptLine(lineNumber = "", text = text, bold = false, alignment = Alignment.LEFT)
    }

    /** Format centimes → "150,00 MAD" */
    private fun formatAmount(centimes: Long): String {
        val whole = centimes / 100
        val dec = centimes % 100
        return "%d,%02d MAD".format(whole, dec)
    }

    /** DDMMYYYY → DD/MM/YYYY */
    private fun formatDate(raw: String?): String {
        if (raw == null || raw.length < 8) return raw ?: "--/--/----"
        return "${raw.substring(0, 2)}/${raw.substring(2, 4)}/${raw.substring(4, 8)}"
    }

    /** HHMMSS → HH:MM:SS */
    private fun formatTime(raw: String?): String {
        if (raw == null || raw.length < 6) return raw ?: "--:--:--"
        return "${raw.substring(0, 2)}:${raw.substring(2, 4)}:${raw.substring(4, 6)}"
    }
}
