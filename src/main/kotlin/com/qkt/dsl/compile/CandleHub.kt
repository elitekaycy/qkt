package com.qkt.dsl.compile

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
    private val syncGroups = CandleSyncGroups()
    private val slots = HubSlots(syncGroups)

    fun register(
        key: HubKey,
        retention: Int,
        strategyId: String,
    ) {
        require(retention >= 1) { "retention must be >= 1: $retention" }
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        slots.register(key, retention, strategyId)
    }

    fun feed(tick: Tick) {
        val matching = slots.forQktSymbol(tick.symbol)
        if (matching != null) {
            for (i in matching.indices) {
                val slot = matching[i]
                if (isObservationSymbol(tick.symbol)) {
                    publishMacroEvent(slot, tick)
                } else {
                    slot.aggregator.onTick(tick)
                }
            }
        }
        if (syncGroups.isNotEmpty()) syncGroups.sweepTimeouts(tick.timestamp)
    }

    private fun publishMacroEvent(
        slot: HubSlot,
        tick: Tick,
    ) {
        val candle = slot.publishObservation(tick)
        syncGroups.route(slot.key, candle)
    }

    /**
     * Late ticks rejected across every slot's aggregator.
     *
     * A hub slot is where a DSL stream's bars are actually built, so a late drop here is a bar
     * that silently disagrees with the venue. Reporting only the default window aggregator hid
     * exactly the drops a multi-stream strategy suffers.
     */
    fun droppedLateTicks(): Long = slots.droppedLateTicks()

    /**
     * Time-driven close: finish every in-progress candle whose window ended at
     * [nowMs], across all keys, and sweep sync timeouts. Live's heartbeat calls this
     * so a quiet symbol's last bar still closes and fires its rules.
     */
    fun flushClosed(nowMs: Long) {
        for (slot in slots.ordered) slot.aggregator.flushClosed(nowMs)
        syncGroups.sweepTimeouts(nowMs)
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
        slot.seed(candles)
    }

    /** Seed [key]'s window in progress; see [com.qkt.candles.CandleAggregator.seedForming]. */
    fun seedForming(
        key: HubKey,
        partial: Candle,
    ) = (slots[key] ?: error("CandleHub.seedForming: unknown key $key")).aggregator.seedForming(partial)

    /**
     * Append a synthetic [candle] to [key]'s ring, trimmed to the slot's retention, and
     * route it into any sync group that contains [key]. Unlike the aggregator path this
     * fires no `onClosed` listeners — it is for a derived stream (e.g. a BASKET composite)
     * whose own rules are driven separately by the producer that computes the candle.
     * Throws if [key] was never registered via [register].
     *
     * Routing lets a strategy `SYNCHRONIZE` a real stream with a basket: the basket's
     * composite reaches the shared group so a `gold.close / basket.close` condition reads
     * the same-window value. e.g. a basket compositor builds the bar for a window and calls
     * `hub.publish(basketKey, composite)` so `basket.close` reads it like any stream.
     */
    fun publish(
        key: HubKey,
        candle: Candle,
    ) {
        val slot = slots[key] ?: error("CandleHub.publish: unknown key $key")
        slot.append(candle)
        syncGroups.route(key, candle)
    }

    fun latest(key: HubKey): Candle? = slots[key]?.ring?.lastOrNull()

    fun history(
        key: HubKey,
        n: Int,
    ): Candle? = slots[key]?.history(n)

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
        slots.unregister(strategyId)
        syncGroups.unregister(strategyId)
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
        syncGroups.register(group, strategyId)
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
        syncGroups.onClosed(group, strategyId, callback)
    }

    fun syncGroupKeys(): Set<SyncGroupKey> = syncGroups.keys()

    fun retention(key: HubKey): Int = slots[key]?.retention ?: 0

    fun historySize(key: HubKey): Int = slots[key]?.ring?.size ?: 0

    /** Immutable oldest-to-newest snapshot used to replay seeded bars into DSL state. */
    fun seededHistory(key: HubKey): List<Candle> = slots[key]?.ring?.toList() ?: emptyList()

    fun latestAtOrBefore(
        key: HubKey,
        endTimeMs: Long,
    ): Candle? = slots[key]?.latestAtOrBefore(endTimeMs)

    fun keys(): Set<HubKey> = slots.keys()
}
