package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

fun interface SymbolPattern {
    fun matches(symbol: String): Boolean

    companion object {
        fun prefix(prefix: String): SymbolPattern = SymbolPattern { it.startsWith(prefix) }

        fun exact(symbol: String): SymbolPattern = SymbolPattern { it == symbol }

        fun exactSet(symbols: Set<String>): SymbolPattern = SymbolPattern { it in symbols }
    }
}

class CompositeMarketSource(
    private val routes: List<Pair<SymbolPattern, MarketSource>>,
    private val fallback: MarketSource,
) : MarketSource {
    override val name: String = "Composite"

    override val capabilities: Set<MarketSourceCapability> =
        (routes.map { it.second.capabilities } + listOf(fallback.capabilities)).flatten().toSet()

    override fun supports(symbol: String): Boolean = sourceFor(symbol).supports(symbol)

    // Per-symbol capabilities come from the leaf that actually serves the symbol, not the union —
    // so e.g. a volume check on an MT5-routed symbol isn't masked by a crypto leaf that has volume.
    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        sourceFor(symbol).capabilitiesFor(symbol)

    private fun sourceFor(symbol: String): MarketSource =
        routes.firstOrNull { (pat, _) -> pat.matches(symbol) }?.second ?: fallback

    override fun liveTicks(symbols: List<String>): TickFeed {
        if (symbols.isEmpty()) {
            throw UnsupportedDataException(
                MarketSourceCapability.LIVE_TICKS,
                "CompositeMarketSource: no symbols supplied",
            )
        }
        val grouped = symbols.groupBy { sourceFor(it) }
        if (grouped.size == 1) {
            return grouped.keys.first().liveTicks(symbols)
        }
        val perVendor =
            grouped.map { (vendor, syms) ->
                VendorTickFeed(vendor.name, syms, vendor.liveTicks(syms))
            }
        return FanInTickFeed(perVendor)
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = sourceFor(symbol).bars(symbol, window, range)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = sourceFor(symbol).ticks(symbol, range)
}

private data class VendorTickFeed(
    val source: String,
    val symbols: List<String>,
    val feed: TickFeed,
)

private class FanInTickFeed(
    private val feeds: List<VendorTickFeed>,
) : TickFeed,
    MarketDataLifecycleFeed {
    private sealed interface Item {
        data class Value(
            val tick: Tick,
        ) : Item

        data class Ended(
            val vendor: VendorTickFeed,
            val failure: String?,
        ) : Item
    }

    private val queue = LinkedBlockingQueue<Item>(QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val threads: List<Thread>
    private var finiteFeedsEnded = 0

    @Volatile
    private var terminalReason: String? = null

    override val expectsContinuousDelivery: Boolean = feeds.any { it.feed is MarketDataLifecycleFeed }

    init {
        feeds.forEach { vendor ->
            (vendor.feed as? MarketDataLifecycleFeed)?.let { lifecycle ->
                lifecycle.onDisconnect {
                    val scope = MarketDataFeedScope(vendor.source, vendor.symbols)
                    disconnectHandlers.forEach { handler -> runCatching { handler(scope) } }
                }
                lifecycle.onReconnect {
                    val scope = MarketDataFeedScope(vendor.source, vendor.symbols)
                    reconnectHandlers.forEach { handler -> runCatching { handler(scope) } }
                }
            }
        }
        threads =
            feeds.mapIndexed { index, vendor ->
                Thread(
                    {
                        try {
                            while (!closed.get()) {
                                val tick = vendor.feed.next()
                                if (tick == null) {
                                    val lifecycle = vendor.feed as? MarketDataLifecycleFeed
                                    val failure =
                                        if (lifecycle?.expectsContinuousDelivery == true) {
                                            lifecycle.terminalFailureReason()
                                                ?: "continuous market-data feed ended"
                                        } else {
                                            null
                                        }
                                    queue.put(Item.Ended(vendor, failure))
                                    return@Thread
                                }
                                queue.put(Item.Value(tick))
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (t: Throwable) {
                            if (!closed.get()) {
                                queue.put(Item.Ended(vendor, "feed reader failed: ${t.message ?: t::class.simpleName}"))
                            }
                        }
                    },
                    "qkt-feed-fanin-$index",
                ).apply {
                    isDaemon = true
                    start()
                }
            }
    }

    override fun next(): Tick? {
        while (!closed.get()) {
            when (val item = queue.take()) {
                is Item.Value -> return item.tick
                is Item.Ended -> {
                    if (item.failure != null) {
                        terminalReason =
                            "market-data feed '${item.vendor.source}' for " +
                            "${item.vendor.symbols.joinToString()} ended: ${item.failure}"
                        close()
                        return null
                    }
                    finiteFeedsEnded++
                    if (finiteFeedsEnded == feeds.size) return null
                }
            }
        }
        return null
    }

    override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
        disconnectHandlers.add(handler)
    }

    override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
        reconnectHandlers.add(handler)
    }

    override fun terminalFailureReason(): String? = terminalReason

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        feeds.forEach { runCatching { it.feed.close() } }
        threads.forEach { it.interrupt() }
    }

    private companion object {
        const val QUEUE_CAPACITY = 10_000
    }
}
