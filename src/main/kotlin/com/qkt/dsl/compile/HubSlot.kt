package com.qkt.dsl.compile

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

/** A closed-candle listener on a [HubSlot], tagged with the strategy that registered it. */
internal data class OwnedListener(
    val strategyId: String,
    val callback: (Candle) -> Unit,
)

/**
 * One `(broker, symbol, timeframe)` stream inside a [CandleHub]: its aggregator, the ring of
 * closed candles kept to the largest [retention] any owner asked for, its listeners and the
 * strategies that own it.
 */
internal class HubSlot(
    val key: HubKey,
    val aggregator: CandleAggregator,
    val ring: ArrayDeque<Candle>,
    var retention: Int,
    val listeners: MutableList<OwnedListener>,
    val owners: MutableSet<String>,
) {
    /** Appends [candle] as the newest bar and trims the ring to [retention]. */
    fun append(candle: Candle) {
        ring.addLast(candle)
        while (ring.size > retention) ring.removeFirst()
    }

    /**
     * Closes an observation tick (MACRO:, HUB:) as its own event candle: appends it and notifies
     * the listeners. Returns the candle so the hub can route it into sync groups.
     */
    fun publishObservation(tick: Tick): Candle {
        val durationMs = TimeWindow.parse(key.timeframe).durationMs
        val candle =
            Candle(
                symbol = tick.symbol,
                open = tick.price,
                high = tick.price,
                low = tick.price,
                close = tick.price,
                volume = tick.volume ?: com.qkt.common.Money.ZERO,
                startTime = tick.timestamp,
                endTime = tick.timestamp + durationMs,
                bid = tick.bid,
                ask = tick.ask,
            )
        append(candle)
        for (listener in listeners.toList()) listener.callback(candle)
        return candle
    }

    /** The bar [n] closes back from the newest (0 = newest), or null outside the ring. */
    fun history(n: Int): Candle? {
        if (n < 0 || n >= ring.size) return null
        return ring[ring.size - 1 - n]
    }

    /** The newest bar whose end time is at or before [endTimeMs], or null. */
    fun latestAtOrBefore(endTimeMs: Long): Candle? {
        for (i in ring.indices.reversed()) {
            val candle = ring[i]
            if (candle.endTime <= endTimeMs) return candle
        }
        return null
    }

    /** Prepends history older than the ring's oldest bar; see [CandleHub.seed]. */
    fun seed(candles: List<Candle>) {
        if (candles.isEmpty()) return
        val expectedDurationMs = TimeWindow.parse(key.timeframe).durationMs
        require(candles.all { it.symbol == key.qktSymbol }) {
            "CandleHub.seed: symbol mismatch for $key"
        }
        require(candles.all { it.endTime - it.startTime == expectedDurationMs }) {
            "CandleHub.seed: timeframe mismatch for $key; expected ${expectedDurationMs}ms bars"
        }
        // Live aggregation and backtests both build bars on the epoch-aligned UTC grid
        // (00:00, 04:00, ... for 4h). A seed on any other phase (e.g. broker-day-aligned
        // MT5 history) would warm indicators on bars no other part of qkt ever produces.
        // An observation stream (MACRO:, HUB:) closes each published value as its own event candle
        // at the instant it became knowable, which is almost never a day boundary. Its history is
        // seeded the same way, so the grid rule -- which exists for aggregated OHLC bars -- must not
        // reject the very shape the live path publishes.
        require(isObservationSymbol(key.qktSymbol) || candles.all { it.startTime % expectedDurationMs == 0L }) {
            "CandleHub.seed: bars for $key are not on the epoch-aligned UTC grid " +
                "(first offender starts at ${candles.first { it.startTime % expectedDurationMs != 0L }.startTime}); " +
                "refusing to warm indicators on a shifted grid"
        }
        val sorted = candles.sortedBy { it.startTime }
        val oldestExisting = ring.firstOrNull()?.startTime ?: Long.MAX_VALUE
        val toPrepend = sorted.filter { it.startTime < oldestExisting }
        for (c in toPrepend.reversed()) ring.addFirst(c)
        while (ring.size > retention) ring.removeFirst()
    }
}
