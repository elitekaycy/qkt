package com.qkt.dsl.compile

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle

/**
 * The [HubSlot]s of a [CandleHub], keyed by stream, plus the symbol index the per-tick feed
 * reads. Registration creates each slot's aggregator, whose close callback appends to the ring,
 * notifies listeners and routes the bar into [syncGroups].
 */
internal class HubSlots(
    private val syncGroups: CandleSyncGroups,
) {
    private val slots: MutableMap<HubKey, HubSlot> = java.util.concurrent.ConcurrentHashMap()

    // Per-tick [CandleHub.feed] reads only the slots for the tick's symbol via this index instead of
    // walking the whole `slots` map and comparing qktSymbol on each. Derived from `slots`; rebuilt on
    // register/unregister (rare) and read on the hot path, so it is published through @Volatile.
    // Longer windows close first at a shared boundary. A faster-stream rule can therefore read the
    // newly closed slower candle, independent of ConcurrentHashMap or registration order.
    @Volatile
    private var slotsByQktSymbol: Map<String, List<HubSlot>> = emptyMap()

    @Volatile
    private var orderedSlots: List<HubSlot> = emptyList()

    /** Every slot, ordered by symbol then longest timeframe first. */
    val ordered: List<HubSlot> get() = orderedSlots

    operator fun get(key: HubKey): HubSlot? = slots[key]

    fun containsKey(key: HubKey): Boolean = slots.containsKey(key)

    fun keys(): Set<HubKey> = slots.keys.toSet()

    fun forQktSymbol(qktSymbol: String): List<HubSlot>? = slotsByQktSymbol[qktSymbol]

    fun droppedLateTicks(): Long = slots.values.sumOf { it.aggregator.droppedLateTicks }

    /** Adds [strategyId] to [key]'s slot, raising its retention, or creates the slot. */
    fun register(
        key: HubKey,
        retention: Int,
        strategyId: String,
    ) {
        val existing = slots[key]
        if (existing != null) {
            existing.retention = maxOf(existing.retention, retention)
            existing.owners.add(strategyId)
            return
        }
        val window = TimeWindow.parse(key.timeframe)
        val ring = ArrayDeque<Candle>()
        val listeners = mutableListOf<OwnedListener>()
        val agg =
            CandleAggregator.standalone(window) { closed ->
                val slot = slots[key] ?: return@standalone
                ring.addLast(closed)
                while (ring.size > slot.retention) ring.removeFirst()
                for (l in slot.listeners.toList()) l.callback(closed)
                syncGroups.route(key, closed)
            }
        slots[key] = HubSlot(key, agg, ring, retention, listeners, mutableSetOf(strategyId))
        rebuildSymbolIndex()
    }

    /** Drops [strategyId]'s listeners and ownership; a slot left with no owner is removed. */
    fun unregister(strategyId: String) {
        val toDrop = mutableListOf<HubKey>()
        for ((key, slot) in slots) {
            slot.listeners.removeAll { it.strategyId == strategyId }
            slot.owners.remove(strategyId)
            if (slot.owners.isEmpty()) toDrop.add(key)
        }
        for (k in toDrop) slots.remove(k)
        if (toDrop.isNotEmpty()) rebuildSymbolIndex()
    }

    private fun rebuildSymbolIndex() {
        val sorted =
            slots.values.sortedWith(
                compareBy<HubSlot> { it.key.qktSymbol }
                    .thenByDescending { TimeWindow.parse(it.key.timeframe).durationMs }
                    .thenBy { it.key.timeframe },
            )
        orderedSlots = sorted
        slotsByQktSymbol = sorted.groupBy { it.key.qktSymbol }
    }
}
