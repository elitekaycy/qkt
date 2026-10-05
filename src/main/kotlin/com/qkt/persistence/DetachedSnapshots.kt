package com.qkt.persistence

// Copies of mutable persisted shapes, taken before a write is queued, so a later change to the live
// collections cannot reach a write still waiting in the [AsyncStatePersistor] queue.

/** [states] with their snapshot lists and last values copied. */
internal fun detachedSequences(states: Map<String, PersistedSequenceState>): Map<String, PersistedSequenceState> =
    states.mapValues { (_, state) ->
        state.copy(
            snapshots = state.snapshots.toList(),
            lastValues = state.lastValues.toMap(),
        )
    }

/** [bindings] with their order and ticket id lists copied. */
internal fun detachedExitHooks(bindings: List<PersistedExitHookBinding>): List<PersistedExitHookBinding> =
    bindings.map {
        it.copy(
            entryOrderIds = it.entryOrderIds.toList(),
            stopOrderIds = it.stopOrderIds.toList(),
            takeProfitOrderIds = it.takeProfitOrderIds.toList(),
            closeOrderIds = it.closeOrderIds.toList(),
            brokerTickets = it.brokerTickets.toList(),
        )
    }
