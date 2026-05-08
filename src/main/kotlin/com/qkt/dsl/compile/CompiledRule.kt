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
) {
    fun fire(
        ec: EvalContext,
        ctx: StrategyContext,
    ): List<Signal> {
        val v = condition.evaluate(ec)
        if (v !is Value.Bool || !v.v) return emptyList()

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
