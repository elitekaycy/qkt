package com.qkt.dsl.compile

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
}

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
        )
}

fun interface CompiledExpr {
    fun evaluate(ctx: EvalContext): Value
}
