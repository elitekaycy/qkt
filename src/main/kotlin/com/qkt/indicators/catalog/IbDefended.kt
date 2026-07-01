package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_DAY = 86_400_000L
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_MINUTE = 60_000L

/**
 * Whether the session's Initial Balance edge has already been tested and held earlier this
 * session — the per-session memory that separates an initiative late break from a naive
 * opening-range breakout.
 *
 * The Initial Balance (IB) is the high/low of the session's first [ibMinutes], where the session
 * starts at [sessionStartHour] UTC each day. After that window closes, the indicator watches the
 * relevant edge ([high] = the IB high, else the IB low): a bar that trades through it but closes
 * back inside is a defended-and-held test. Once that happens it reads 1 for the rest of the
 * session (the responsive edge held, so a later break is initiative flow overwhelming it); until
 * then it reads 0. It resets each session.
 *
 * Pair it with `session_range_high`/`session_range_low` for the IB level itself. Derived purely
 * from candle timestamps and prices, so it is deterministic and reads identically in backtest and
 * live. Value is null until the IB window has elapsed with an IB captured this session.
 */
class IbDefended(
    private val sessionStartHour: Int,
    private val ibMinutes: Int,
    private val high: Boolean,
) : Indicator<Candle> {
    init {
        require(sessionStartHour in 0..23) { "IbDefended.sessionStartHour must be in 0..23: $sessionStartHour" }
        require(ibMinutes > 0) { "IbDefended.ibMinutes must be > 0: $ibMinutes" }
    }

    private val startOffsetMs = sessionStartHour * MS_PER_HOUR
    private val ibMs = ibMinutes * MS_PER_MINUTE
    private var session: Long = Long.MIN_VALUE
    private var ibHigh: BigDecimal? = null
    private var ibLow: BigDecimal? = null
    private var pastWindow = false
    private var defended = false

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = pastWindow && ibExtreme() != null

    override fun update(input: Candle) {
        val idx = Math.floorDiv(input.startTime - startOffsetMs, MS_PER_DAY)
        if (idx != session) {
            session = idx
            ibHigh = null
            ibLow = null
            pastWindow = false
            defended = false
        }
        val msIntoSession = Math.floorMod(input.startTime - startOffsetMs, MS_PER_DAY)
        if (msIntoSession < ibMs) {
            ibHigh = ibHigh?.max(input.high) ?: input.high
            ibLow = ibLow?.min(input.low) ?: input.low
        } else {
            pastWindow = true
            val edge = ibExtreme()
            if (!defended && edge != null && testedAndHeld(input, edge)) {
                defended = true
            }
        }
    }

    private fun ibExtreme(): BigDecimal? = if (high) ibHigh else ibLow

    private fun testedAndHeld(
        input: Candle,
        edge: BigDecimal,
    ): Boolean =
        if (high) {
            input.high >= edge && input.close < edge
        } else {
            input.low <= edge && input.close > edge
        }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        return if (defended) BigDecimal.ONE else BigDecimal.ZERO
    }
}
