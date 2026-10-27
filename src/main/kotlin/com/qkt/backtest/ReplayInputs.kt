package com.qkt.backtest

import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.SplitAdjustedActions
import com.qkt.instrument.SplitAdjustment
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SplitAdjustedMarketSource
import java.time.Instant

/**
 * The market a replay reads, checked and prepared once: its window [from]-[to] ([range]), a [source] serving ticks or
 * bars and the [instruments] it trades. When a symbol of the run split before the run's end, its prices ([SplitAdjustedMarketSource]) and its
 * corporate actions ([SplitAdjustedActions]) are read through one [SplitAdjustment], so they cannot disagree; a run
 * with no split reads exactly the source and instruments it was given.
 */
internal class ReplayInputs private constructor(
    val from: Instant,
    val to: Instant,
    val source: MarketSource,
    val instruments: InstrumentRegistry,
) {
    val range: TimeRange get() = TimeRange(from, to)

    companion object {
        fun of(
            source: MarketSource,
            instruments: InstrumentRegistry,
            request: MarketRequest,
            calendar: TradingCalendar,
        ): ReplayInputs {
            require(
                MarketSourceCapability.TICKS in source.capabilities ||
                    MarketSourceCapability.BARS in source.capabilities,
            ) {
                "Backtest requires a MarketSource with TICKS or BARS; ${source.name} has ${source.capabilities}"
            }
            val from = request.from ?: error("Backtest.fromSource requires explicit MarketRequest.from")
            val to = request.to ?: error("Backtest.fromSource requires explicit MarketRequest.to")
            val splits =
                SplitAdjustment.of(instruments.corporateActions(), request.symbols, calendar, to.toEpochMilli())
            if (splits.isEmpty()) return ReplayInputs(from, to, source, instruments)
            return ReplayInputs(
                from,
                to,
                SplitAdjustedMarketSource(source, splits),
                SplitAdjustedActions(instruments, splits, calendar),
            )
        }
    }
}
