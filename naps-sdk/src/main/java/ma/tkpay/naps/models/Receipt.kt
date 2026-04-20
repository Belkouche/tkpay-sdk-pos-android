package ma.tkpay.naps.models

/**
 * Receipt data structure
 *
 * @property lines List of receipt lines
 * @property type Receipt type (MERCHANT or CUSTOMER)
 */
data class Receipt(
    val lines: List<ReceiptLine>,
    val type: ReceiptType
) {
    /**
     * Get receipt as plain text
     */
    fun toPlainText(): String = lines.joinToString("\n") { it.text }

    /**
     * Get receipt with formatting markers
     */
    fun toFormattedText(): String = lines.joinToString("\n") {
        val prefix = if (it.bold) "[B]" else ""
        val align = when (it.alignment) {
            Alignment.CENTER -> "[C]"
            Alignment.RIGHT -> "[R]"
            Alignment.LEFT -> "[L]"
        }
        "$prefix$align${it.text}"
    }
}

/**
 * Single line in a receipt
 *
 * @property lineNumber Line number from terminal
 * @property text Line content
 * @property bold Whether text should be bold
 * @property alignment Text alignment
 */
data class ReceiptLine(
    val lineNumber: String,
    val text: String,
    val bold: Boolean = false,
    val alignment: Alignment = Alignment.LEFT
)

/**
 * Text alignment options
 */
enum class Alignment {
    LEFT,
    CENTER,
    RIGHT
}

/**
 * Receipt type
 */
enum class ReceiptType {
    MERCHANT,
    CUSTOMER
}
