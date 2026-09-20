package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.WarmupStream

/** Serves one parity tape to both runs: its ticks live and historically, and the warmup bars per stream. */
internal class TapeSource(
    private val ticks: List<Tick>,
    private val warmupCandles: List<Candle>,
    private val warmupByStream: Map<WarmupStream, List<Candle>>,
) : MarketSource {
    override val name: String = "DslParityTape"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(
            MarketSourceCapability.TICKS,
            MarketSourceCapability.LIVE_TICKS,
            MarketSourceCapability.BARS,
            MarketSourceCapability.VOLUME,
        )

    override fun supports(symbol: String): Boolean = true

    override fun liveTicks(symbols: List<String>): TickFeed =
        object : TickFeed {
            private var index = 0

            override fun next(): Tick? = if (index < ticks.size) ticks[index++] else null

            override fun close() = Unit
        }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> =
        ticks.asSequence().filter {
            it.symbol == symbol &&
                it.timestamp >= range.from.toEpochMilli() &&
                it.timestamp < range.to.toEpochMilli()
        }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: com.qkt.common.TimeRange,
    ): Sequence<Candle> =
        (warmupByStream[WarmupStream(symbol, window)] ?: warmupCandles)
            .asSequence()
            .filter {
                it.symbol == symbol &&
                    it.startTime >= range.from.toEpochMilli() &&
                    it.startTime < range.to.toEpochMilli()
            }
}
