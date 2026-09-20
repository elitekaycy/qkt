package com.qkt.dsl.compile

import com.qkt.marketdata.Candle

/**
 * The `SYNCHRONIZE` groups registered on a [CandleHub]: for each group, the owning strategies,
 * the listeners waiting for an atomic close, and the partial windows still waiting for a member's
 * bar. The hub routes every closed bar here and sweeps expired windows on each tick.
 */
internal class CandleSyncGroups {
    private data class OwnedSyncListener(
        val strategyId: String,
        val callback: (Map<String, Candle>) -> Unit,
    )

    private class SyncSlot(
        val key: SyncGroupKey,
        val listeners: MutableList<OwnedSyncListener>,
        val owners: MutableSet<String>,
        // window-end (ms) -> alias -> closed bar. Cleared per-window on atomic fire or timeout.
        val pending: MutableMap<Long, MutableMap<String, Candle>> = mutableMapOf(),
    )

    private val syncSlots: MutableMap<SyncGroupKey, SyncSlot> =
        java.util.concurrent.ConcurrentHashMap()

    fun isNotEmpty(): Boolean = syncSlots.isNotEmpty()

    /** Adds [strategyId] as an owner of [group], creating the group on first registration. */
    fun register(
        group: SyncGroupKey,
        strategyId: String,
    ) {
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

    /** Subscribes [callback] to atomic closes of [group]; throws if the group is unknown. */
    fun onClosed(
        group: SyncGroupKey,
        strategyId: String,
        callback: (Map<String, Candle>) -> Unit,
    ) {
        val slot = syncSlots[group] ?: error("CandleHub.onSyncClosed: unknown sync group $group")
        slot.listeners.add(OwnedSyncListener(strategyId, callback))
    }

    /**
     * Route a closed bar into every sync group that contains [streamKey]. Stash the
     * bar under the group's pending map keyed by `closed.endTime`. If every member of
     * the group now has a bar for that window-end, fire the listeners once with one
     * `(alias -> bar)` entry per member and clear the window.
     *
     * Called from each per-stream close callback. Safe to call when no sync group
     * references [streamKey] — the walk just skips.
     */
    fun route(
        streamKey: HubKey,
        closed: Candle,
    ) {
        for ((_, syncSlot) in syncSlots) {
            val alias =
                syncSlot.key.members.entries
                    .firstOrNull { it.value == streamKey }
                    ?.key
                    ?: continue
            val window = syncSlot.pending.getOrPut(closed.endTime) { mutableMapOf() }
            window[alias] = closed
            if (window.size == syncSlot.key.members.size) {
                val snapshot = window.toMap()
                syncSlot.pending.remove(closed.endTime)
                for (l in syncSlot.listeners.toList()) l.callback(snapshot)
            }
        }
    }

    /**
     * Drop any pending sync window whose end time is more than the group's
     * `timeoutMs` behind [nowTs]. No callback fires — the partial window is GC'd.
     *
     * Called once per tick from [CandleHub.feed]. Groups with `timeoutMs == null` keep
     * partial windows forever, which is fine if both streams reliably print.
     */
    fun sweepTimeouts(nowTs: Long) {
        for ((_, syncSlot) in syncSlots) {
            val tm = syncSlot.key.timeoutMs ?: continue
            val expired = syncSlot.pending.keys.filter { endTime -> nowTs > endTime + tm }
            for (et in expired) syncSlot.pending.remove(et)
        }
    }

    /** Drops [strategyId]'s listeners and ownership; a group left with no owner is removed. */
    fun unregister(strategyId: String) {
        val syncToDrop = mutableListOf<SyncGroupKey>()
        for ((key, slot) in syncSlots) {
            slot.listeners.removeAll { it.strategyId == strategyId }
            slot.owners.remove(strategyId)
            if (slot.owners.isEmpty()) syncToDrop.add(key)
        }
        for (k in syncToDrop) syncSlots.remove(k)
    }

    fun keys(): Set<SyncGroupKey> = syncSlots.keys.toSet()
}
