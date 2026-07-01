package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

private const val MS_PER_HOUR = 3_600_000L

/**
 * The price gap opened across a trading break (e.g. the weekend), and how much of it has since
 * filled — the size and non-fill signals a gap-continuation edge needs.
 *
 * A "reopen" is the first bar whose start follows the previous bar's end by more than
 * [minGapHours] — the market was closed in between (weekend, holiday). At that bar the indicator
 * latches:
 * - [origin] — the last close before the break (the level a full fill returns to),
 * - [size] — signed gap = reopen open minus origin (positive = gapped up), sized against ATR by
 *   the caller,
 * - [fillFraction] — how far price has since retraced from the reopen open back toward origin,
 *   in gap units: 0 at the reopen, 1 when price is back at origin (a full fill). It stays signed
 *   correctly for down gaps.
 *
 * All three hold until the next reopen. Boundaries and levels come purely from candle timestamps
 * and prices, so it is deterministic and reads identically in backtest and live. e.g. Friday close
 * 101, Sunday reopen 105 → size +4, origin 101; a later 103 print → fillFraction 0.5.
 */
class ReopenGap(
    private val minGapHours: Int,
) : Indicator<Candle> {
    init {
        require(minGapHours > 0) { "ReopenGap.minGapHours must be > 0: $minGapHours" }
    }

    private val minGapMs = minGapHours * MS_PER_HOUR
    private var prevClose: BigDecimal? = null
    private var prevEndTime: Long? = null
    private var gapOrigin: BigDecimal? = null
    private var reopenOpen: BigDecimal? = null
    private var lastClose: BigDecimal? = null

    override val warmupBars: Int = 1

    override val isReady: Boolean
        get() = gapOrigin != null

    override fun update(input: Candle) {
        val pe = prevEndTime
        val pc = prevClose
        if (pe != null && pc != null && input.startTime - pe > minGapMs) {
            gapOrigin = pc
            reopenOpen = input.open
        }
        lastClose = input.close
        prevClose = input.close
        prevEndTime = input.endTime
    }

    /** Signed gap size (reopen open minus origin), or null before the first reopen. */
    fun size(): BigDecimal? {
        val o = gapOrigin ?: return null
        val r = reopenOpen ?: return null
        return r.subtract(o, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    /** The pre-break close a full fill returns to, or null before the first reopen. */
    fun origin(): BigDecimal? = gapOrigin

    /** Retracement toward origin in gap units (0 at reopen, 1 at a full fill), or null. */
    fun fillFraction(): BigDecimal? {
        val o = gapOrigin ?: return null
        val r = reopenOpen ?: return null
        val c = lastClose ?: return null
        val gap = r.subtract(o, Money.CONTEXT)
        if (gap.signum() == 0) return null
        return r.subtract(c, Money.CONTEXT).divide(gap, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun value(): BigDecimal? = size()
}
