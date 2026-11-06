package ma.tkpay.naps.protocol

import java.io.ByteArrayOutputStream

/**
 * Binary TLV protocol for Sunmi P2/P3 terminals via TCP socket.
 * Wire format: [2-byte length BE][tag(1) + length(2 BE) + value(N)]...
 *
 * This is the binary framing used by some Sunmi deployments, distinct from the
 * ASCII TLV wire format handled by [TlvProtocol]. It is exposed so integrators
 * can build and parse the binary frames directly.
 */
object BinaryTlvProtocol {

    // Tags
    const val TAG_TXN_TYPE: Byte = 0x01
    const val TAG_AMOUNT: Byte = 0x02
    const val TAG_ORDER_ID: Byte = 0x03
    const val TAG_RESPONSE_CODE: Byte = 0x10
    const val TAG_AUTH_NUMBER: Byte = 0x11
    const val TAG_CARD_NUMBER: Byte = 0x12
    const val TAG_CARD_SCHEME: Byte = 0x13
    const val TAG_RRN: Byte = 0x14
    const val TAG_STAN: Byte = 0x15
    const val TAG_TERMINAL_ID: Byte = 0x16
    const val TAG_TXN_DATE: Byte = 0x17
    const val TAG_TXN_TIME: Byte = 0x18
    const val TAG_APPROVAL_CODE: Byte = 0x19
    const val TAG_RESPONSE_MSG: Byte = 0x1A

    const val TXN_TYPE_SALE = "01"
    const val TXN_TYPE_REVERSAL = "02"
    const val TXN_TYPE_CONFIRMATION = "03"
    const val TXN_TYPE_CANCELLATION = "04"

    /**
     * Build a TLV request body (txn type + amount + order id), without the length prefix
     */
    fun buildRequest(txnType: String, amount: String, reference: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        appendTlv(buffer, TAG_TXN_TYPE, txnType.toByteArray(Charsets.US_ASCII))
        appendTlv(buffer, TAG_AMOUNT, amount.toByteArray(Charsets.US_ASCII))
        appendTlv(buffer, TAG_ORDER_ID, reference.toByteArray(Charsets.US_ASCII))
        return buffer.toByteArray()
    }

    /**
     * Prefix a TLV body with its 2-byte big-endian length
     */
    fun wrapWithLength(tlvData: ByteArray): ByteArray {
        val result = ByteArray(2 + tlvData.size)
        result[0] = (tlvData.size shr 8).toByte()
        result[1] = (tlvData.size and 0xFF).toByte()
        System.arraycopy(tlvData, 0, result, 2, tlvData.size)
        return result
    }

    /**
     * Parse a binary TLV response body (no length prefix) into a tag → value map
     */
    fun parseResponse(data: ByteArray): Map<Byte, String> {
        val fields = mutableMapOf<Byte, String>()
        var offset = 0

        while (offset + 2 < data.size) {
            val tag = data[offset++]
            if (offset + 1 >= data.size) break
            val length = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (offset + length > data.size) break
            fields[tag] = String(data, offset, length, Charsets.US_ASCII)
            offset += length
        }

        return fields
    }

    private fun appendTlv(buffer: ByteArrayOutputStream, tag: Byte, value: ByteArray) {
        buffer.write(tag.toInt())
        buffer.write(value.size shr 8)
        buffer.write(value.size and 0xFF)
        buffer.write(value)
    }
}
