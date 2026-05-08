package com.qkt.dsl.compile

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

class CandleHub {
    private class Slot(
        val aggregator: CandleAggregator,
        val ring: ArrayDeque<Candle>,
        var retention: Int,
        val listeners: MutableList<(Candle) -> Unit>,
    )

    private val slots: MutableMap<HubKey, Slot> = LinkedHashMap()
    private var feedStarted: Boolean = false

    fun register(
        key: HubKey,
        retention: Int,
    ) {
        require(retention >= 1) { "retention must be >= 1: $retention" }
        check(!feedStarted) { "CandleHub.register called after feed started: $key" }
        val existing = slots[key]
        if (existing != null) {
            existing.retention = maxOf(existing.retention, retention)
            return
        }
        val window = TimeWindow.parse(key.timeframe)
        val ring = ArrayDeque<Candle>()
        val listeners = mutableListOf<(Candle) -> Unit>()
        val agg =
            CandleAggregator.standalone(window) { closed ->
                val slot = slots[key]!!
                ring.addLast(closed)
                while (ring.size > slot.retention) ring.removeFirst()
                listeners.forEach { it(closed) }
            }
        slots[key] = Slot(agg, ring, retention, listeners)
    }

    fun feed(tick: Tick) {
        feedStarted = true
        for ((key, slot) in slots) {
            if (key.symbol == tick.symbol) slot.aggregator.onTick(tick)
        }
    }

    fun latest(key: HubKey): Candle? = slots[key]?.ring?.lastOrNull()

    fun history(
        key: HubKey,
        n: Int,
    ): Candle? {
        val ring = slots[key]?.ring ?: return null
        if (n < 0 || n >= ring.size) return null
        return ring[ring.size - 1 - n]
    }

    fun onClosed(
        key: HubKey,
        callback: (Candle) -> Unit,
    ) {
        val slot = slots[key] ?: error("CandleHub.onClosed: unknown key $key")
        slot.listeners.add(callback)
    }

    fun retention(key: HubKey): Int = slots[key]?.retention ?: 0

    fun historySize(key: HubKey): Int = slots[key]?.ring?.size ?: 0

    fun keys(): Set<HubKey> = slots.keys.toSet()
}
