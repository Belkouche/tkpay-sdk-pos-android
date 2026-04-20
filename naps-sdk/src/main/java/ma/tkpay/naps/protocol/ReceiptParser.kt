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
     */
    fun parse(dpValue: String, type: ReceiptType): Receipt {
        val lines = mutableListOf<ReceiptLine>()
        var index = 0
        var currentLine = ReceiptLine("", "", false, Alignment.LEFT)
        var lineNumber = ""
        var format = "S"
        var alignment = "G"
        var content = ""

        while (index < dpValue.length) {
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
                    // New line starting
                    if (lineNumber.isNotEmpty() && content.isNotEmpty()) {
                        // Save previous line
                        lines.add(createReceiptLine(lineNumber, content, format, alignment))
                    }
                    lineNumber = value
                    format = "S"
                    alignment = "G"
                    content = ""
                }
                TlvProtocol.ReceiptTags.FORMAT -> {
                    format = value
                }
                TlvProtocol.ReceiptTags.ALIGNMENT -> {
                    alignment = value
                }
                TlvProtocol.ReceiptTags.CONTENT -> {
                    content = value
                }
            }

            index += 6 + length
        }

        // Don't forget the last line
        if (lineNumber.isNotEmpty() && content.isNotEmpty()) {
            lines.add(createReceiptLine(lineNumber, content, format, alignment))
        }

        // Apply TKPAY branding
        val brandedLines = applyBranding(lines)

        // Mask any card numbers in receipt
        val maskedLines = brandedLines.map { line ->
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

    /**
     * Apply TKPAY branding to receipt
     * Replace first "Naps" header with "TKPAY" and add "Powered by NAPS"
     */
    private fun applyBranding(lines: List<ReceiptLine>): List<ReceiptLine> {
        val result = mutableListOf<ReceiptLine>()
        var brandingApplied = false

        for (line in lines) {
            if (!brandingApplied &&
                line.text.contains("Naps", ignoreCase = true) &&
                line.alignment == Alignment.CENTER) {

                // Replace with TKPAY header
                result.add(
                    ReceiptLine(
                        lineNumber = line.lineNumber,
                        text = "TKPAY",
                        bold = true,
                        alignment = Alignment.CENTER
                    )
                )

                // Add "Powered by NAPS" below
                result.add(
                    ReceiptLine(
                        lineNumber = (line.lineNumber.toIntOrNull()?.plus(1)?.toString()?.padStart(2, '0')) ?: "00",
                        text = "Powered by NAPS",
                        bold = false,
                        alignment = Alignment.CENTER
                    )
                )

                brandingApplied = true
            } else {
                result.add(line)
            }
        }

        return result
    }
}
