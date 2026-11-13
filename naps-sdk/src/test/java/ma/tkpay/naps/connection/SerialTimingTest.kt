package ma.tkpay.naps.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialTimingTest {
    private val opened = 1_000_000L
    private val pay = "001003001002003100"
    private val confirm = "001003002003007"

    @Test
    fun firstFrameWaitsUntilPortOpen32Seconds() {
        assertEquals(32_000L, SerialTiming.remainingWait(opened, 0, opened, pay))
        assertEquals(2_000L, SerialTiming.remainingWait(opened, 0, opened + 30_000, pay))
        assertEquals(0L, SerialTiming.remainingWait(opened, 0, opened + 32_000, pay))
    }

    @Test
    fun newOperationWaitsThreeSecondsAfterTheLastReply() {
        // Lane3600: a TM 003 right after TM 102, or a TM 001 0.5 s after TM 109, was lost.
        val lastRx = opened + 50_000
        assertEquals(3_000L, SerialTiming.remainingWait(opened, lastRx, lastRx, pay))
        assertEquals(2_000L, SerialTiming.remainingWait(opened, lastRx, lastRx + 1_000, pay))
        assertEquals(0L, SerialTiming.remainingWait(opened, lastRx, lastRx + 3_000, pay))
    }

    @Test
    fun confirmationWaitsHalfASecondAfterTheLastReply() {
        val lastRx = opened + 50_000
        assertEquals(500L, SerialTiming.remainingWait(opened, lastRx, lastRx, confirm))
        assertEquals(0L, SerialTiming.remainingWait(opened, lastRx, lastRx + 500, confirm))
    }

    @Test
    fun onlyTm002AndTm004AreConfirmations() {
        assertTrue(SerialTiming.isPhase2("001003002003007"))
        assertTrue(SerialTiming.isPhase2("001003004003007"))
        assertFalse(SerialTiming.isPhase2("001003001002003"))
        assertFalse(SerialTiming.isPhase2("001003003003007"))
        assertFalse(SerialTiming.isPhase2("001003009003007"))
    }
}
