package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.ExitReason
import com.qkt.marketdata.Candle
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

private val EMPTY_SNAPSHOT_STORE = SnapshotStore(emptyMap())
private val EMPTY_CANDLE_HUB = CandleHub()

sealed interface Value {
    data class Num(
        val v: BigDecimal,
    ) : Value

    data class Bool(
        val v: Boolean,
    ) : Value

    data class Str(
        val v: String,
    ) : Value

    data object Undefined : Value

    companion object {
        /** The two Bool values, shared — comparisons produce one per rule-eval otherwise. */
        val TRUE: Bool = Bool(true)
        val FALSE: Bool = Bool(false)

        fun of(b: Boolean): Bool = if (b) TRUE else FALSE
    }
}

/** Immutable closing-fill values exposed to one exit-hook evaluation. */
data class ExitContext(
    val price: BigDecimal,
    val side: Side,
    val quantity: BigDecimal,
    val pnl: BigDecimal,
    val reason: ExitReason,
)

class EvalContext(
    val candle: Candle,
    val streams: Map<String, HubKey>,
    val lets: Map<String, BigDecimal>,
    val strategyContext: StrategyContext,
    val snapshotStore: SnapshotStore = EMPTY_SNAPSHOT_STORE,
    val hub: CandleHub = EMPTY_CANDLE_HUB,
    val currentAlias: String? = null,
    /**
     * The parent fill/entry price, set only while evaluating an OTO (`ON_FILL`) child order so
     * its prices can reference the parent via the `entry` keyword. Null in every other context.
     */
    val entryPrice: BigDecimal? = null,
    /** Event time for deterministic bar-close evaluation; null uses the runtime clock. */
    val evaluationTimeMs: Long? = null,
    /** Warmup replay frontier for cross-stream reads; null uses the live latest value. */
    val historyAsOfMs: Long? = null,
    /** DSL `SEQUENCE` state visible during the current rule evaluation pass. */
    val sequences: SequenceStateView = NoopSequenceStateView,
    /** Closing-fill context; non-null only while an exit hook is executing. */
    val exitContext: ExitContext? = null,
) {
    fun nowMs(): Long = evaluationTimeMs ?: strategyContext.clock.now()

    /** A copy of this context with [entryPrice] bound — used when building OTO child orders. */
    fun withEntryPrice(entryPrice: BigDecimal): EvalContext =
        EvalContext(
            candle,
            streams,
            lets,
            strategyContext,
            snapshotStore,
            hub,
            currentAlias,
            entryPrice,
            evaluationTimeMs,
            historyAsOfMs,
            sequences,
            exitContext,
        )

    /** A copy of this context evaluated at [timeMs], used for delayed signal builders. */
    fun atEvaluationTime(timeMs: Long): EvalContext =
        EvalContext(
            candle,
            streams,
            lets,
            strategyContext,
            snapshotStore,
            hub,
            currentAlias,
            entryPrice,
            timeMs,
            historyAsOfMs,
            sequences,
            exitContext,
        )
}

fun interface CompiledExpr {
    fun evaluate(ctx: EvalContext): Value
}
