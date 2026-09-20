package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.MarketDataFeedScope
import com.qkt.marketdata.live.MarketDataLifecycleFeed
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Canonical-prefix [MarketSource] that records every request [PrefixRemapMarketSource] forwards. */
internal class RemapRecordingSource(
    private val lifecycle: Boolean = true,
) : MarketSource {
    override val name: String = "canonical"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

    val supportsQueries = CopyOnWriteArrayList<String>()
    val capabilitiesQueries = CopyOnWriteArrayList<String>()
    val liveRequests = CopyOnWriteArrayList<List<String>>()
    val barRequests = CopyOnWriteArrayList<String>()
    val tickRequests = CopyOnWriteArrayList<String>()
    val tickSliceRequests = CopyOnWriteArrayList<String>()

    lateinit var feed: RemapControllableFeed
    lateinit var plainFeed: RemapPlainFeed

    override fun supports(symbol: String): Boolean {
        supportsQueries.add(symbol)
        return symbol.startsWith("S0:")
    }

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> {
        capabilitiesQueries.add(symbol)
        return setOf(MarketSourceCapability.LIVE_TICKS)
    }

    override fun liveTicks(symbols: List<String>): TickFeed {
        liveRequests.add(symbols)
        return if (lifecycle) {
            RemapControllableFeed().also { feed = it }
        } else {
            RemapPlainFeed().also { plainFeed = it }
        }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        barRequests.add(symbol)
        return sequenceOf(
            Candle(
                symbol = symbol,
                open = BigDecimal("1.0"),
                high = BigDecimal("1.1"),
                low = BigDecimal("0.9"),
                close = BigDecimal("1.05"),
                volume = BigDecimal("10"),
                startTime = range.from.toEpochMilli(),
                endTime = range.to.toEpochMilli(),
            ),
        )
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        tickRequests.add(symbol)
        return sequenceOf(Tick(symbol, BigDecimal("1.00000"), range.from.toEpochMilli()))
    }

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        tickSliceRequests.add(symbol)
        return sequenceOf(Tick(symbol, BigDecimal("1.00000"), fromMs))
    }
}

/** Lifecycle feed a test drives by hand: emit ticks, disconnect, reconnect, fail. */
internal class RemapControllableFeed :
    TickFeed,
    MarketDataLifecycleFeed {
    private sealed interface Item {
        data class Value(
            val tick: Tick,
        ) : Item

        data object End : Item
    }

    private val queue = LinkedBlockingQueue<Item>()
    val closed = AtomicBoolean(false)
    private val disconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()
    private val reconnectHandlers = CopyOnWriteArrayList<(MarketDataFeedScope) -> Unit>()

    @Volatile
    private var failure: String? = null

    fun emit(tick: Tick) {
        queue.put(Item.Value(tick))
    }

    fun disconnect(scope: MarketDataFeedScope) {
        disconnectHandlers.forEach { it(scope) }
    }

    fun reconnect(scope: MarketDataFeedScope) {
        reconnectHandlers.forEach { it(scope) }
    }

    fun fail(reason: String) {
        failure = reason
        queue.put(Item.End)
    }

    override fun next(): Tick? =
        when (val item = queue.take()) {
            is Item.Value -> item.tick
            Item.End -> null
        }

    override fun onDisconnect(handler: (MarketDataFeedScope) -> Unit) {
        disconnectHandlers.add(handler)
    }

    override fun onReconnect(handler: (MarketDataFeedScope) -> Unit) {
        reconnectHandlers.add(handler)
    }

    override fun terminalFailureReason(): String? = failure

    override fun close() {
        closed.set(true)
        queue.offer(Item.End)
    }
}

/** Plain [TickFeed] with no lifecycle contract, fed by hand. */
internal class RemapPlainFeed : TickFeed {
    private val queue = LinkedBlockingQueue<Tick>()
    val closed = AtomicBoolean(false)

    fun emit(tick: Tick) {
        queue.put(tick)
    }

    override fun next(): Tick? = queue.take()

    override fun close() {
        closed.set(true)
    }
}
