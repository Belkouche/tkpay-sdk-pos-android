package ma.tkpay.naps.protocol

import ma.tkpay.naps.models.Alignment
import ma.tkpay.naps.models.Receipt
import ma.tkpay.naps.models.ReceiptLine
import ma.tkpay.naps.models.ReceiptType

/**
 * Parser for NAPS Pay receipt data (DP tag / 010)
 *
 * Receipt data contains sub-tags:
 * - 030: Line number (2 chars)
 * - 031: Format (S=Simple, G=Gras/Bold)
 * - 032: Alignment (C=Center, D=Droite/Right, G=Gauche/Left)
 * - 033: Content (variable length)
 */
object ReceiptParser {

    /**
     * Parse receipt data from DP tag value
     *
     * Spec §5: line groups are separated by '*' and '?' ends the block. Every
     * line is kept, in order, as sent — including lines with empty text. The only
     * change made to the text is masking a full PAN, should one ever appear.
     */
    fun parse(dpValue: String, type: ReceiptType): Receipt {
        val lines = mutableListOf<ReceiptLine>()
        var index = 0
        var inLine = false
        var lineNumber = ""
        var format = "S"
        var alignment = "G"
        var content = ""

        fun endLine() {
            if (inLine) lines.add(createReceiptLine(lineNumber, content, format, alignment))
            inLine = false
            lineNumber = ""
            format = "S"
            alignment = "G"
            content = ""
        }

        while (index < dpValue.length) {
            if (dpValue[index] == '*') { endLine(); index++; continue }
            if (dpValue[index] == '?') break

            // Need at least 6 chars for tag + length
            if (index + 6 > dpValue.length) break

            val tag = dpValue.substring(index, index + 3)
            val lengthStr = dpValue.substring(index + 3, index + 6)

            // Validate length is numeric
            if (!lengthStr.all { it.isDigit() }) break

            val length = lengthStr.toInt()

            // Check if we have enough data
            if (index + 6 + length > dpValue.length) break

            val value = dpValue.substring(index + 6, index + 6 + length)

            when (tag) {
                TlvProtocol.ReceiptTags.LINE_NUMBER -> {
                    // A new line number without a '*' before it still starts a new line
                    endLine()
                    lineNumber = value
                }
                TlvProtocol.ReceiptTags.FORMAT -> format = value
                TlvProtocol.ReceiptTags.ALIGNMENT -> alignment = value
                TlvProtocol.ReceiptTags.CONTENT -> content = value
            }
            inLine = true

            index += 6 + length
        }

        // Don't forget the last line
        endLine()

        // Mask any card numbers in receipt
        val maskedLines = lines.map { line ->
            line.copy(text = TlvProtocol.maskCardNumbersInText(line.text))
        }

        return Receipt(maskedLines, type)
    }

    /**
     * Create receipt line from parsed data
     */
    private fun createReceiptLine(
        lineNumber: String,
        content: String,
        format: String,
        alignmentCode: String
    ): ReceiptLine {
        val bold = format == "G"  // G = Gras (Bold)
        val alignment = when (alignmentCode) {
            "C" -> Alignment.CENTER
            "D" -> Alignment.RIGHT  // Droite
            "G" -> Alignment.LEFT   // Gauche
            else -> Alignment.LEFT
        }

        return ReceiptLine(
            lineNumber = lineNumber,
            text = content,
            bold = bold,
            alignment = alignment
        )
    }
}
