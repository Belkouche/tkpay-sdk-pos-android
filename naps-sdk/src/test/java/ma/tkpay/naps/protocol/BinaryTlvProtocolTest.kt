package ma.tkpay.naps.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryTlvProtocolTest {

    @Test
    fun buildRequest_encodesTagLengthValue() {
        val body = BinaryTlvProtocol.buildRequest(BinaryTlvProtocol.TXN_TYPE_SALE, "1000", "ORD1")
        val expected = byteArrayOf(
            0x01, 0x00, 0x02, '0'.code.toByte(), '1'.code.toByte(),
            0x02, 0x00, 0x04, '1'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
            0x03, 0x00, 0x04, 'O'.code.toByte(), 'R'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte()
        )
        assertArrayEquals(expected, body)
    }

    @Test
    fun wrapWithLength_prefixesBigEndianLength() {
        val wrapped = BinaryTlvProtocol.wrapWithLength(ByteArray(300))
        assertEquals(302, wrapped.size)
        assertEquals(0x01, wrapped[0].toInt())
        assertEquals(0x2C, wrapped[1].toInt())
    }

    @Test
    fun parseResponse_roundTripsBuiltRequest() {
        val body = BinaryTlvProtocol.buildRequest(BinaryTlvProtocol.TXN_TYPE_CANCELLATION, "25000", "REF-42")
        val fields = BinaryTlvProtocol.parseResponse(body)
        assertEquals("04", fields[BinaryTlvProtocol.TAG_TXN_TYPE])
        assertEquals("25000", fields[BinaryTlvProtocol.TAG_AMOUNT])
        assertEquals("REF-42", fields[BinaryTlvProtocol.TAG_ORDER_ID])
    }

    @Test
    fun parseResponse_stopsOnTruncatedValue() {
        val data = byteArrayOf(0x10, 0x00, 0x03, '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(),
            0x15, 0x00, 0x06, '1'.code.toByte())
        val fields = BinaryTlvProtocol.parseResponse(data)
        assertEquals(mapOf(BinaryTlvProtocol.TAG_RESPONSE_CODE to "000"), fields)
    }
}
