package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

class CompiledRule(
    private val condition: CompiledExpr,
    private val action: (EvalContext) -> List<Signal>,
    val ruleAlias: String,
    private val ruleSymbol: String,
    private val isBuy: Boolean,
    private val isSell: Boolean,
    private val onBuyCaptures: List<Pair<String, CompiledExpr>>,
    private val onSellCaptures: List<Pair<String, CompiledExpr>>,
    private val onOpenCaptures: List<Pair<String, CompiledExpr>>,
    val referencedAliases: Set<String>,
) {
    // Action gating is edge-driven (reference/dsl/conditions.md): the action runs on the
    // false->true transition of the condition, not on every bar it stays true. Without
    // this, `WHEN close > ema THEN BUY` emits an entry on all 50 bars the close holds
    // above the EMA. Undefined counts as false, so a condition that goes true ->
    // Undefined -> true re-arms and fires again.
    private var wasTrue = false

    fun fire(
        ec: EvalContext,
        ctx: StrategyContext,
    ): List<Signal> {
        val v = condition.evaluate(ec)
        val isTrue = v is Value.Bool && v.v
        val rising = isTrue && !wasTrue
        wasTrue = isTrue
        if (!rising) return emptyList()

        val preFireQty =
            ctx.positions.positionFor(ruleSymbol)?.quantity ?: BigDecimal.ZERO
        val isOpening = preFireQty.signum() == 0 && (isBuy || isSell)

        if (isBuy) capture(onBuyCaptures, SnapshotBuy, ec)
        if (isSell) capture(onSellCaptures, SnapshotSell, ec)
        if (isOpening) capture(onOpenCaptures, SnapshotOpen, ec)

        return action(ec)
    }

    private fun capture(
        captures: List<Pair<String, CompiledExpr>>,
        kind: com.qkt.dsl.ast.SnapshotKind,
        ec: EvalContext,
    ) {
        for ((name, e) in captures) {
            val r = e.evaluate(ec)
            if (r is Value.Num) ec.snapshotStore.captureSlot(ruleAlias, name, kind, r.v)
        }
    }
}
