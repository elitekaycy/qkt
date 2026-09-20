package com.qkt.dsl.compile

import com.qkt.persistence.PersistedSequenceSnapshot
import com.qkt.persistence.PersistedSequenceState

/**
 * One `SEQUENCE`'s live progress inside a [SequenceRuntime]: the current stage, the close and
 * time snapshotted at each completed stage, each stage condition's last value for edge
 * detection, and the one-pass completion pulse. Converts to and from its persisted form.
 */
internal data class SequenceProgress(
    var stage: Int = 0,
    val snapshots: MutableMap<String, SequenceSnapshot> = linkedMapOf(),
    val lastValues: MutableMap<String, Boolean> = mutableMapOf(),
    var completePulse: Boolean = false,
) {
    fun reset() {
        stage = 0
        snapshots.clear()
        lastValues.clear()
        completePulse = false
    }

    /** True when the current stage's WITHIN window, measured from the last completed stage, has passed. */
    fun timedOut(
        sequence: CompiledSequence,
        nowMs: Long,
    ): Boolean {
        val current = sequence.stages.getOrNull(stage) ?: return false
        val within = current.withinMs ?: return false
        if (stage == 0) return false
        val previous = snapshots.values.lastOrNull() ?: return false
        return nowMs - previous.timeMs > within
    }

    /** Replaces this progress with the persisted [state]. */
    fun restoreFrom(state: PersistedSequenceState) {
        stage = state.stage
        completePulse = state.completePulse
        snapshots.clear()
        for (snap in state.snapshots) {
            snapshots[snap.stage] = SequenceSnapshot(snap.stage, snap.price, snap.timeMs)
        }
        lastValues.clear()
        lastValues.putAll(state.lastValues)
    }

    /** The persisted form, with snapshots in [sequence]'s stage order. */
    fun toPersisted(
        name: String,
        sequence: CompiledSequence,
    ): PersistedSequenceState =
        PersistedSequenceState(
            name = name,
            stage = stage,
            snapshots =
                sequence.stages.mapNotNull { stage ->
                    snapshots[stage.name]?.let {
                        PersistedSequenceSnapshot(it.stage, it.price, it.timeMs)
                    }
                },
            lastValues = lastValues.toMap(),
            completePulse = completePulse,
        )
}
