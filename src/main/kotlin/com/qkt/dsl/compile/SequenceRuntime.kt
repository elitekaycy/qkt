package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.persistence.PersistedSequenceState
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal

/**
 * Stateful executor for DSL `SEQUENCE` declarations.
 *
 * The runtime advances only on the declared candle-close stream, requires a
 * fresh false-to-true edge for each current stage, snapshots stage close/time,
 * and exposes a one-pass completion pulse to rules before resetting.
 */
class SequenceRuntime(
    private val sequences: List<CompiledSequence>,
) : SequenceStateView {
    private val byName = sequences.associateBy { it.name }
    private val states = sequences.associate { it.name to SequenceProgress() }.toMutableMap()
    private var persistor: StatePersistor? = null
    private var strategyId: String = ""
    private var ruleEdges: List<CompiledRule> = emptyList()
    private var restoredRuleEdges: Map<String, Boolean> = emptyMap()

    internal fun bindRuleEdges(rules: List<CompiledRule>) {
        ruleEdges = rules
        restoreRuleEdges()
    }

    /** Attach durable state and restore any persisted sequence progress for [strategyId]. */
    fun bindPersistor(
        strategyId: String,
        persistor: StatePersistor,
    ) {
        this.strategyId = strategyId
        this.persistor = persistor
        val persisted = runCatching { persistor.loadSequences(strategyId) }.getOrDefault(emptyMap())
        restoredRuleEdges = persisted[RULE_EDGE_STATE]?.lastValues.orEmpty()
        restoreRuleEdges()
        for ((name, state) in persisted) {
            val runtime = states[name] ?: continue
            runtime.restoreFrom(state)
        }
    }

    private fun restoreRuleEdges() {
        if (restoredRuleEdges.isEmpty() || ruleEdges.isEmpty()) return
        for (rule in ruleEdges) {
            restoredRuleEdges[rule.edgeStateKey]?.let(rule::restoreEdgeState)
        }
    }

    /** Clear every rule edge, including any not yet restored, and persist the cleared state. */
    internal fun clearRuleEdges() {
        restoredRuleEdges = emptyMap()
        for (rule in ruleEdges) rule.clearEdge()
        persistRuleEdges()
    }

    internal fun persistRuleEdges() {
        var dirty = false
        for (rule in ruleEdges) {
            if (rule.consumeEdgeDirty()) dirty = true
        }
        if (!dirty) return
        persist()
    }

    /**
     * Advance matching sequences for a closed [candle].
     *
     * [streamAlias] disambiguates duplicate aliases that share a symbol. [isWarm]
     * gates advancement until all aliases read by a sequence are warmed.
     */
    fun onCandle(
        candle: Candle,
        ec: EvalContext,
        streamAlias: String? = null,
        isWarm: (Set<String>) -> Boolean = { true },
    ) {
        for (sequence in sequences) {
            if (streamAlias != null && sequence.streamAlias != streamAlias) continue
            if (sequence.streamSymbol != candle.symbol) continue
            if (!isWarm(sequence.referencedAliases)) continue
            advance(sequence, stateFor(sequence.name), candle, ec)
        }
    }

    /**
     * Clear completion pulses after rules have observed them. [deferCompletion] keeps a
     * pulse live when every consuming signal was suppressed, allowing the re-armed rule
     * to retry on the next bar.
     */
    fun afterRulePass(deferCompletion: Boolean = false) {
        if (deferCompletion) return
        var changed = false
        for ((name, state) in states) {
            if (!state.completePulse) continue
            state.reset()
            changed = true
        }
        if (changed) persist()
    }

    override fun stage(sequence: String): Int = states[sequence]?.stage ?: 0

    override fun complete(sequence: String): Boolean = states[sequence]?.completePulse ?: false

    override fun stagePrice(
        sequence: String,
        stage: String,
    ): BigDecimal? = states[sequence]?.snapshots?.get(stage)?.price

    override fun stageTime(
        sequence: String,
        stage: String,
    ): Long? = states[sequence]?.snapshots?.get(stage)?.timeMs

    private fun advance(
        sequence: CompiledSequence,
        state: SequenceProgress,
        candle: Candle,
        ec: EvalContext,
    ) {
        if (state.completePulse) return

        val currentValues =
            sequence.stages.associate { stage ->
                val value = (stage.condition.evaluate(ec) as? Value.Bool)?.v ?: false
                stage.name to value
            }
        val previousValues = sequence.stages.associate { it.name to (state.lastValues[it.name] ?: false) }
        var changed = false
        for ((name, value) in currentValues) {
            if (state.lastValues[name] != value) {
                state.lastValues[name] = value
                changed = true
            }
        }

        val timedOut = state.timedOut(sequence, candle.endTime)
        if (timedOut) {
            state.reset()
            changed = true
        }
        val next = state.stage
        if (next !in sequence.stages.indices) {
            if (changed) persist()
            return
        }
        val stage = sequence.stages[next]
        val currentValue = currentValues[stage.name] ?: false
        val previousValue = if (timedOut) false else previousValues[stage.name] ?: false
        if (!currentValue || previousValue) {
            if (changed) persist()
            return
        }
        state.stage = next + 1
        state.snapshots[stage.name] = SequenceSnapshot(stage.name, candle.close, candle.endTime)
        if (state.stage == sequence.stages.size) state.completePulse = true
        persist()
    }

    private fun stateFor(name: String): SequenceProgress = states.getOrPut(name) { SequenceProgress() }

    private fun persist() {
        val p = persistor ?: return
        if (strategyId.isBlank()) return
        val persisted =
            states
                .mapValues { (name, state) -> state.toPersisted(name, byName.getValue(name)) }
                .toMutableMap()
        if (ruleEdges.isNotEmpty()) {
            persisted[RULE_EDGE_STATE] =
                PersistedSequenceState(
                    name = RULE_EDGE_STATE,
                    stage = 0,
                    snapshots = emptyList(),
                    lastValues = ruleEdges.associate { it.edgeStateKey to it.edgeState },
                )
        }
        runCatching { p.saveSequences(strategyId, persisted) }
    }

    private companion object {
        const val RULE_EDGE_STATE = "__qkt_rule_edges__"
    }
}
