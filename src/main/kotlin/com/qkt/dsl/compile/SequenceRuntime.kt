package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.persistence.PersistedSequenceSnapshot
import com.qkt.persistence.PersistedSequenceState
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal

/**
 * Compiled representation of one `SEQUENCE` stage.
 *
 * [withinMs] is measured from the previous completed stage. A null value means
 * the stage can complete any time after it becomes current.
 */
data class CompiledSequenceStage(
    val name: String,
    val withinMs: Long?,
    val condition: CompiledExpr,
)

/**
 * Compiled runtime metadata for a DSL `SEQUENCE` declaration.
 *
 * [referencedAliases] includes the sequence's `ON` stream and any aliases read by
 * stage conditions. The runtime uses it to avoid advancing a sequence before all
 * referenced streams are warmed.
 */
data class CompiledSequence(
    val name: String,
    val streamAlias: String,
    val streamSymbol: String,
    val stages: List<CompiledSequenceStage>,
    val referencedAliases: Set<String>,
)

/** Snapshot captured when a sequence stage completes. */
data class SequenceSnapshot(
    val stage: String,
    val price: BigDecimal,
    val timeMs: Long,
)

/** Read-only view exposed to compiled DSL expressions for `SEQUENCE.*` accessors. */
interface SequenceStateView {
    /** Zero-based current stage index, or the stage count while completion is pulsing. */
    fun stage(sequence: String): Int

    /** True only for the rule pass immediately after the final stage completes. */
    fun complete(sequence: String): Boolean

    /** Close price captured when [stage] completed, or null if it has not completed. */
    fun stagePrice(
        sequence: String,
        stage: String,
    ): BigDecimal?

    /** Candle close time captured when [stage] completed, or null if it has not completed. */
    fun stageTime(
        sequence: String,
        stage: String,
    ): Long?
}

/** Empty sequence state used when a compiled expression is evaluated outside a DSL runtime. */
object NoopSequenceStateView : SequenceStateView {
    override fun stage(sequence: String): Int = 0

    override fun complete(sequence: String): Boolean = false

    override fun stagePrice(
        sequence: String,
        stage: String,
    ): BigDecimal? = null

    override fun stageTime(
        sequence: String,
        stage: String,
    ): Long? = null
}

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
    private data class RuntimeState(
        var stage: Int = 0,
        val snapshots: MutableMap<String, SequenceSnapshot> = linkedMapOf(),
        val lastValues: MutableMap<String, Boolean> = mutableMapOf(),
        var completePulse: Boolean = false,
    )

    private val byName = sequences.associateBy { it.name }
    private val states = sequences.associate { it.name to RuntimeState() }.toMutableMap()
    private var persistor: StatePersistor? = null
    private var strategyId: String = ""

    /** Attach durable state and restore any persisted sequence progress for [strategyId]. */
    fun bindPersistor(
        strategyId: String,
        persistor: StatePersistor,
    ) {
        this.strategyId = strategyId
        this.persistor = persistor
        val persisted = runCatching { persistor.loadSequences(strategyId) }.getOrDefault(emptyMap())
        for ((name, state) in persisted) {
            val runtime = states[name] ?: continue
            runtime.stage = state.stage
            runtime.completePulse = state.completePulse
            runtime.snapshots.clear()
            for (snap in state.snapshots) {
                runtime.snapshots[snap.stage] = SequenceSnapshot(snap.stage, snap.price, snap.timeMs)
            }
            runtime.lastValues.clear()
            runtime.lastValues.putAll(state.lastValues)
        }
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

    /** Clear any completion pulses after rules have had exactly one pass to observe them. */
    fun afterRulePass() {
        var changed = false
        for ((name, state) in states) {
            if (!state.completePulse) continue
            reset(state)
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
        state: RuntimeState,
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

        val timedOut = timedOut(sequence, state, candle.endTime)
        if (timedOut) {
            reset(state)
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

    private fun timedOut(
        sequence: CompiledSequence,
        state: RuntimeState,
        nowMs: Long,
    ): Boolean {
        val stage = sequence.stages.getOrNull(state.stage) ?: return false
        val within = stage.withinMs ?: return false
        if (state.stage == 0) return false
        val previous = state.snapshots.values.lastOrNull() ?: return false
        return nowMs - previous.timeMs > within
    }

    private fun reset(state: RuntimeState) {
        state.stage = 0
        state.snapshots.clear()
        state.lastValues.clear()
        state.completePulse = false
    }

    private fun stateFor(name: String): RuntimeState = states.getOrPut(name) { RuntimeState() }

    private fun persist() {
        val p = persistor ?: return
        if (strategyId.isBlank()) return
        val persisted =
            states.mapValues { (name, state) ->
                val sequence = byName.getValue(name)
                PersistedSequenceState(
                    name = name,
                    stage = state.stage,
                    snapshots =
                        sequence.stages.mapNotNull { stage ->
                            state.snapshots[stage.name]?.let {
                                PersistedSequenceSnapshot(it.stage, it.price, it.timeMs)
                            }
                        },
                    lastValues = state.lastValues.toMap(),
                    completePulse = state.completePulse,
                )
            }
        runCatching { p.saveSequences(strategyId, persisted) }
    }
}
