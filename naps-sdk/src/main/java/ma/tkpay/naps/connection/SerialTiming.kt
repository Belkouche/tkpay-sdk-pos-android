package ma.tkpay.naps.connection

/**
 * When a USB serial frame may be written to an Ingenico Lane3600. Found on a live terminal:
 *
 * - It holds back every answer until ~30 s after the port opens (the first frame's reply always
 *   landed at 30.1 s). A TM 101 delayed that way arrived after the terminal had given up waiting
 *   for TM 002 ("attente validation caisse" timeout), so the payment was never recorded.
 * - After a reply it keeps showing or printing the result for a moment, and a request arriving
 *   then is lost: a TM 003 sent right after TM 102 got no reply, and a TM 001 sent 0.5 s after
 *   TM 109 was refused with CR 100 without asking for a card. The same requests sent 3 s later
 *   completed. Confirmations (TM 002, TM 004), which the terminal is waiting for, need only 0.5 s.
 */
internal object SerialTiming {
    const val READY_MS = 32_000L
    const val IDLE_MS = 3_000L
    const val SETTLE_MS = 500L

    /** TM 002 / TM 004: the confirmation the terminal is waiting for, not a new operation. */
    fun isPhase2(tlvMessage: String): Boolean =
        tlvMessage.startsWith("001003002") || tlvMessage.startsWith("001003004")

    /** Milliseconds to wait before writing [tlvMessage]. */
    fun remainingWait(openedAt: Long, lastRxAt: Long, now: Long, tlvMessage: String): Long {
        val quiet = if (isPhase2(tlvMessage)) SETTLE_MS else IDLE_MS
        return maxOf(openedAt + READY_MS, lastRxAt + quiet, now) - now
    }
}
