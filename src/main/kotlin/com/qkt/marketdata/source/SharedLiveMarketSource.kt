package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shares one upstream live feed per symbol across every subscriber of a daemon-level source.
 *
 * Portfolio children are separate live sessions, but commonly consume the same venue symbols.
 * Without fan-out, each child opens another HTTP/WS subscription and multiplies venue load by
 * the number of strategies. Historical reads remain direct because they are bounded requests.
 */
class SharedLiveMarketSource(
    private val delegate: MarketSource,
    private val subscriberQueueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val clock: com.qkt.common.Clock = com.qkt.common.SystemClock(),
) : MarketSource,
    AutoCloseable {
    override val name: String = delegate.name
    override val capabilities: Set<MarketSourceCapability> = delegate.capabilities

    private val closed = AtomicBoolean(false)
    private val hubs = ConcurrentHashMap<String, SharedSymbolHub>()

    override fun supports(symbol: String): Boolean = delegate.supports(symbol)

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> = delegate.capabilitiesFor(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed {
        check(!closed.get()) { "$name is closed" }
        val distinct = symbols.distinct()
        require(distinct.isNotEmpty()) { "$name requires at least one live symbol" }
        require(distinct.all(delegate::supports)) { "$name cannot serve $distinct" }

        val feeds = distinct.map(::subscribe)
        return if (feeds.size == 1) feeds.single() else SharedFanInTickFeed(feeds)
    }

    private fun subscribe(symbol: String): SharedSubscriberFeed {
        while (true) {
            val hub =
                hubs.compute(symbol) { _, current ->
                    current?.takeUnless { it.isClosed() }
                        ?: SharedSymbolHub(
                            delegate,
                            name,
                            symbol,
                            subscriberQueueCapacity,
                            clock,
                        ) { ended -> hubs.remove(symbol, ended) }
                }!!
            hub.subscribe()?.let { return it }
        }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = delegate.bars(symbol, window, range)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = delegate.ticks(symbol, range)

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> = delegate.tickSlice(symbol, fromMs, toMs)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        hubs.values.forEach { runCatching { it.close() } }
        hubs.clear()
        (delegate as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY: Int = 10_000
    }
}
