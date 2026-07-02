package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

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

class EvalContext(
    val candle: Candle,
    val streams: Map<String, HubKey>,
    val lets: Map<String, BigDecimal>,
    val strategyContext: StrategyContext,
    // Shared empty defaults: EvalContext is constructed per rule evaluation on some paths, and
    // fresh SnapshotStore/CandleHub defaults allocated real maps each time.
    val snapshotStore: SnapshotStore = EMPTY_SNAPSHOTS,
    val hub: CandleHub = EMPTY_HUB,
    val currentAlias: String? = null,
    /**
     * The parent fill/entry price, set only while evaluating an OTO (`ON_FILL`) child order so
     * its prices can reference the parent via the `entry` keyword. Null in every other context.
     */
    val entryPrice: BigDecimal? = null,
) {
    /** A copy of this context with [entryPrice] bound — used when building OTO child orders. */
    fun withEntryPrice(entryPrice: BigDecimal): EvalContext =
        EvalContext(candle, streams, lets, strategyContext, snapshotStore, hub, currentAlias, entryPrice)

    private companion object {
        // Never written through the default path: contexts that need real stores pass their own.
        val EMPTY_SNAPSHOTS = SnapshotStore(emptyMap())
        val EMPTY_HUB = CandleHub()
    }
}

fun interface CompiledExpr {
    fun evaluate(ctx: EvalContext): Value
}
