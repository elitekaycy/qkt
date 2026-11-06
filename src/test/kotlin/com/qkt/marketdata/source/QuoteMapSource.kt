package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.instrument.SlotFamilyFixture
import com.qkt.marketdata.Tick
import java.time.Instant

/** Stored quote ticks by symbol, served in time order inside the requested window; counts each symbol it opens. */
internal class QuoteMapSource(
    ticks: List<Tick>,
) : MarketSource {
    private val bySymbol = ticks.groupBy { it.symbol }.mapValues { (_, t) -> t.sortedBy { it.timestamp } }
    val opened = mutableListOf<String>()

    override val name: String = "quotes"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.TICKS)

    override fun supports(symbol: String): Boolean = symbol in bySymbol

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        opened += symbol
        val from = range.from.toEpochMilli()
        val to = range.to.toEpochMilli()
        return bySymbol[symbol].orEmpty().asSequence().filter { it.timestamp in from until to }
    }

    companion object {
        /** 2024-03-04 from [fromIso] to [toIso], UTC. */
        fun range(
            fromIso: String = "2024-03-04T00:00:00Z",
            toIso: String = "2024-03-05T00:00:00Z",
        ): TimeRange = TimeRange(Instant.parse(fromIso), Instant.parse(toIso))

        /** 2024-03-04 at [hhmm] (`10:01`) in epoch millis. */
        fun at(hhmm: String): Long = SlotFamilyFixture.ms("2024-03-04T$hhmm:00Z")
    }
}
