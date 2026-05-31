package com.qkt.dsl.compile

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

/**
 * Shared candle aggregation backplane for DSL strategies.
 *
 * Lives on the trading pipeline; every DSL strategy registers the streams it cares
 * about and gets called back whenever a closed candle prints on those streams.
 * Multiple strategies sharing the same `(broker, symbol, timeframe)` triple share the
 * same aggregator — deduplicated, JIT-registered.
 *
 * Writes are forward-only (no out-of-order history rewrites). Per-key retention is the
 * max requested by any strategy.
 *
 * Lifecycle: every [register] and [onClosed] is attributed to a `strategyId`. When that
 * strategy stops, [unregister] removes its listeners and drops any slot it was the sole
 * owner of — so a daemon that cycles strategies does not accumulate idle aggregators.
 */
class CandleHub {
    private data class OwnedListener(
        val strategyId: String,
        val callback: (Candle) -> Unit,
    )

    private class Slot(
        val aggregator: CandleAggregator,
        val ring: ArrayDeque<Candle>,
        var retention: Int,
        val listeners: MutableList<OwnedListener>,
        val owners: MutableSet<String>,
    )

    private val slots: MutableMap<HubKey, Slot> = java.util.concurrent.ConcurrentHashMap()

    private data class OwnedSyncListener(
        val strategyId: String,
        val callback: (Map<String, Candle>) -> Unit,
    )

    private class SyncSlot(
        val key: SyncGroupKey,
        val listeners: MutableList<OwnedSyncListener>,
        val owners: MutableSet<String>,
    )

    private val syncSlots: MutableMap<SyncGroupKey, SyncSlot> =
        java.util.concurrent.ConcurrentHashMap()

    fun register(
        key: HubKey,
        retention: Int,
        strategyId: String,
    ) {
        require(retention >= 1) { "retention must be >= 1: $retention" }
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
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
            }
        slots[key] = Slot(agg, ring, retention, listeners, mutableSetOf(strategyId))
    }

    fun feed(tick: Tick) {
        for ((key, slot) in slots) {
            if (key.qktSymbol == tick.symbol) slot.aggregator.onTick(tick)
        }
    }

    /**
     * Bulk-load historical [candles] into the ring at registered [key]. Prepend-only:
     * any candle whose `startTime >= oldest-existing` is dropped, so this never
     * disturbs the live tail. After insertion the ring is truncated to the slot's
     * retention, keeping the newest bars. `onClosed` callbacks are not invoked —
     * `seed` is intended to run before strategies bind their listeners.
     *
     * Used by [com.qkt.app.IndicatorWarmer] / `LiveSession.start()` to satisfy
     * lookback (`btc.close[N]`) the moment the live engine starts.
     */
    fun seed(
        key: HubKey,
        candles: List<Candle>,
    ) {
        val slot = slots[key] ?: error("CandleHub.seed: unknown key $key")
        if (candles.isEmpty()) return
        val sorted = candles.sortedBy { it.startTime }
        val oldestExisting = slot.ring.firstOrNull()?.startTime ?: Long.MAX_VALUE
        val toPrepend = sorted.filter { it.startTime < oldestExisting }
        for (c in toPrepend.reversed()) slot.ring.addFirst(c)
        while (slot.ring.size > slot.retention) slot.ring.removeFirst()
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
        strategyId: String,
        callback: (Candle) -> Unit,
    ) {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        val slot = slots[key] ?: error("CandleHub.onClosed: unknown key $key")
        slot.listeners.add(OwnedListener(strategyId, callback))
    }

    /**
     * Drop every listener and slot-ownership entry attributed to [strategyId]. A slot
     * whose owner set becomes empty is removed entirely — its aggregator and ring fall
     * out of scope and are GC'd. Sync groups owned by the same strategy are dropped on
     * the same rule. Idempotent and safe to call from a stop path.
     */
    fun unregister(strategyId: String) {
        val toDrop = mutableListOf<HubKey>()
        for ((key, slot) in slots) {
            slot.listeners.removeAll { it.strategyId == strategyId }
            slot.owners.remove(strategyId)
            if (slot.owners.isEmpty()) toDrop.add(key)
        }
        for (k in toDrop) slots.remove(k)

        val syncToDrop = mutableListOf<SyncGroupKey>()
        for ((key, slot) in syncSlots) {
            slot.listeners.removeAll { it.strategyId == strategyId }
            slot.owners.remove(strategyId)
            if (slot.owners.isEmpty()) syncToDrop.add(key)
        }
        for (k in syncToDrop) syncSlots.remove(k)
    }

    /**
     * Register a sync group for [strategyId]. Members must already be registered as
     * regular streams via [register]. Multiple strategies may share the same group;
     * the slot is dropped only when its last owner is unregistered.
     *
     * No bars fire from this call — the per-tick atomic-fire path lands in Task 5.
     */
    fun registerSyncGroup(
        group: SyncGroupKey,
        strategyId: String,
    ) {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        for ((alias, key) in group.members) {
            require(slots.containsKey(key)) {
                "registerSyncGroup: alias '$alias' refers to unregistered stream $key"
            }
        }
        val existing = syncSlots[group]
        if (existing != null) {
            existing.owners.add(strategyId)
            return
        }
        syncSlots[group] =
            SyncSlot(
                key = group,
                listeners = mutableListOf(),
                owners = mutableSetOf(strategyId),
            )
    }

    /**
     * Subscribe [callback] to fire once per atomic close of [group]. The callback
     * receives the closed bar per alias (`mapOf("gold" to bar, "silver" to bar)`).
     * Throws if [group] was never registered via [registerSyncGroup].
     */
    fun onSyncClosed(
        group: SyncGroupKey,
        strategyId: String,
        callback: (Map<String, Candle>) -> Unit,
    ) {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        val slot = syncSlots[group] ?: error("CandleHub.onSyncClosed: unknown sync group $group")
        slot.listeners.add(OwnedSyncListener(strategyId, callback))
    }

    fun syncGroupKeys(): Set<SyncGroupKey> = syncSlots.keys.toSet()

    fun retention(key: HubKey): Int = slots[key]?.retention ?: 0

    fun historySize(key: HubKey): Int = slots[key]?.ring?.size ?: 0

    fun keys(): Set<HubKey> = slots.keys.toSet()
}
