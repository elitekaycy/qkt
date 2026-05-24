package com.qkt.marketdata.source

import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.nio.file.Path

/**
 * `MarketSource` that streams ticks from a CSV file as if they were arriving live.
 *
 * Used by CI to verify that a deployed strategy actually processes live market data
 * without depending on a third-party WebSocket (Bybit, TradingView). Ticks are emitted
 * as fast as the consumer can read them — pacing is deliberately uncontrolled because
 * the strategy's candle aggregator drives bar closures off `tick.timestamp`, not the
 * wall clock, so a fixture with timestamps spanning N seconds produces the same closed
 * candles whether the consumer reads in 1 ms or 1 minute.
 *
 * Capability: [MarketSourceCapability.LIVE_TICKS] only. Use [com.qkt.marketdata.source.LocalMarketSource]
 * for historical range queries.
 *
 * Routing: `supports(symbol)` returns `true` for every symbol. In a composite setup the
 * replay source is registered as the fallback; routes match the venue-prefixed brokers
 * first, anything unmatched falls through to replay. Production deployments should never
 * select `source: replay`.
 */
class ReplayMarketSource(
    private val csvPath: Path,
) : MarketSource {
    override val name: String = "Replay"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String): Boolean = true

    override fun liveTicks(symbols: List<String>): TickFeed {
        if (symbols.isEmpty()) {
            throw UnsupportedDataException(
                MarketSourceCapability.LIVE_TICKS,
                "ReplayMarketSource: no symbols supplied",
            )
        }
        val raw = CsvTickFeed(csvPath)
        return SymbolFilteringTickFeed(raw, symbols.toSet())
    }
}

private class SymbolFilteringTickFeed(
    private val source: TickFeed,
    private val allowed: Set<String>,
) : TickFeed {
    override fun next(): Tick? {
        while (true) {
            val t = source.next() ?: return null
            if (t.symbol in allowed) return t
        }
    }

    override fun close() = source.close()
}
