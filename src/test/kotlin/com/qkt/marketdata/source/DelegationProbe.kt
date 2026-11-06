package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.marks.MarkPrices
import java.math.BigDecimal
import java.time.Instant

/**
 * A source that records every call a wrapper forwards to it and answers each with a value a test can
 * recognise: one tick at timestamp 1 for `ticks`, one at 2 for `tickSlice`, a bar per `bars` call.
 */
internal class DelegationProbe(
    override val name: String = "probe",
    private val marks: MarkPrices? = null,
    private val flow: TradeFlow? = null,
) : MarketSource,
    AutoCloseable {
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.BARS)
    val calls = mutableListOf<String>()
    var closed = 0
    var barFailure: Throwable? = null
    val feed =
        object : TickFeed {
            override fun next(): Tick? = null
        }

    override fun supports(symbol: String): Boolean = true

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> {
        calls += "capabilitiesFor:$symbol"
        return setOf(MarketSourceCapability.TICKS)
    }

    override fun marksFor(symbol: String): MarkPrices? = marks

    override fun tradeFlowFor(symbol: String): TradeFlow? = flow

    override fun liveTicks(symbols: List<String>): TickFeed {
        calls += "liveTicks:$symbols"
        return feed
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        calls += "bars:$symbol:${window.durationMs}"
        barFailure?.let { throw it }
        val start = range.from.toEpochMilli()
        return sequenceOf(probeCandle(symbol, start, start + window.durationMs, calls.size))
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        calls += "ticks:$symbol"
        return sequenceOf(Tick(symbol, BigDecimal.ONE, 1L))
    }

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        calls += "tickSlice:$symbol:$fromMs:$toMs"
        return sequenceOf(Tick(symbol, BigDecimal.ONE, 2L))
    }

    override fun close() {
        closed++
    }
}

internal fun probeCandle(
    symbol: String,
    start: Long,
    end: Long,
    close: Int = 1,
): Candle {
    val price = BigDecimal(close)
    return Candle(symbol, price, price, price, price, BigDecimal.ZERO, start, end)
}

internal fun probeRange(
    fromMs: Long = 0L,
    toMs: Long = 60_000L,
): TimeRange = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))
