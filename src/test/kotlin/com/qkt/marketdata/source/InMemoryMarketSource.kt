package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.atomic.AtomicBoolean

open class InMemoryMarketSource(
    override val name: String = "InMemory",
) : MarketSource {
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    private val liveTicksBySymbol: MutableMap<String, MutableList<Tick>> = mutableMapOf()
    private val barsByKey: MutableMap<Pair<String, TimeWindow>, List<Candle>> = mutableMapOf()
    private val supportedSymbols: MutableSet<String> = mutableSetOf()

    fun seedLive(
        symbol: String,
        ticks: List<Tick>,
    ) {
        supportedSymbols.add(symbol)
        liveTicksBySymbol.getOrPut(symbol) { mutableListOf() }.addAll(ticks)
    }

    fun seedBars(
        symbol: String,
        window: TimeWindow,
        candles: List<Candle>,
    ) {
        supportedSymbols.add(symbol)
        barsByKey[symbol to window] = candles
    }

    override fun supports(symbol: String): Boolean = symbol in supportedSymbols

    override fun liveTicks(symbols: List<String>): TickFeed {
        val merged: MutableList<Tick> =
            symbols
                .flatMap { liveTicksBySymbol[it].orEmpty() }
                .sortedBy { it.timestamp }
                .toMutableList()
        val closed = AtomicBoolean(false)

        return object : TickFeed {
            override fun next(): Tick? {
                if (closed.get()) return null
                if (merged.isEmpty()) return null
                return merged.removeAt(0)
            }

            override fun close() {
                closed.set(true)
            }
        }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val all = barsByKey[symbol to window] ?: return emptySequence()
        return all
            .asSequence()
            .filter { it.startTime >= range.from.toEpochMilli() && it.endTime <= range.to.toEpochMilli() }
    }
}
