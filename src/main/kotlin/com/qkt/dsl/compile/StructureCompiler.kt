package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.derivatives.options.chain.ChainView
import com.qkt.derivatives.options.chain.LegSpec
import com.qkt.derivatives.options.chain.StructurePlan
import com.qkt.derivatives.options.chain.StructurePlanner
import com.qkt.dsl.ast.OpenStructure
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StructureLegRight
import com.qkt.dsl.ast.StructureLegSide
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionRight
import com.qkt.instrument.OptionSymbols
import com.qkt.strategy.Signal
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compiles `OPEN <alias> = OPTIONS ON <root> { … } SIZING …`. When the rule fires, the legs are
 * selected from the root's latest chain snapshot at or before the clock ([StructurePlanner]) and sized:
 * `SIZING <qty>` is contracts per leg; `SIZING n PCT RISK` is `equity × n%` over the structure's
 * maximum loss per contract (refused when that loss is unbounded), floored to the volume step. The
 * legs leave as one [Signal.SubmitGroup] of market orders. A leg that selects nothing, or a size below
 * the venue minimum, fires a [Signal.Suppressed] with the reason instead of a partial structure.
 */
internal class StructureCompiler(
    private val exprCompiler: ExprCompiler,
    private val ids: IdGenerator,
) {
    private var views: Pair<InstrumentRegistry, ChainView>? = null

    fun compile(action: OpenStructure): (EvalContext) -> List<Signal> {
        val size: (EvalContext, BigDecimal?) -> BigDecimal? =
            when (val sizing = action.sizing) {
                is SizeQty ->
                    exprCompiler.compile(sizing.expr).let { e ->
                        { ec, _ -> (e.evaluate(ec) as? Value.Num)?.v }
                    }
                is SizeRiskFrac ->
                    exprCompiler.compile(sizing.frac).let { e ->
                        { ec, maxLoss ->
                            val frac = (e.evaluate(ec) as? Value.Num)?.v
                            if (frac == null || maxLoss == null || maxLoss.signum() <= 0) {
                                null
                            } else {
                                ec.strategyContext.pnl
                                    .equity()
                                    .multiply(
                                        frac,
                                        Money.CONTEXT,
                                    ).divide(maxLoss, Money.CONTEXT)
                            }
                        }
                    }
                else -> throw CompileError("structure ${action.alias} sizes by contracts per leg or N PCT RISK")
            }
        val specs =
            action.legs.map { leg ->
                val side = if (leg.side == StructureLegSide.BUY) Side.BUY else Side.SELL
                val right = if (leg.right == StructureLegRight.CALL) OptionRight.CALL else OptionRight.PUT
                LegSpec(side, right, leg.delta.toDouble(), leg.minDays?.toDouble(), leg.maxDays?.toDouble())
            }
        return { ec -> listOf(fire(action, specs, size, ec)) }
    }

    private fun fire(
        action: OpenStructure,
        specs: List<LegSpec>,
        size: (EvalContext, BigDecimal?) -> BigDecimal?,
        ec: EvalContext,
    ): Signal {
        // The clock, not the candle's end: a bar's rules run when its window closes, and a candle's end
        // can lie ahead of the clock (a chain snapshot inside the window would be read early).
        val now = ec.strategyContext.clock.now()
        val instruments = ec.strategyContext.instruments
        val options = instruments.options()
        val root =
            options?.root(action.root)
                ?: return Signal.Suppressed(action.root, "${action.root} is not a declared option root")
        val snapshot =
            view(instruments).latest(root.root, now)
                ?: return Signal.Suppressed(root.root, "no ${root.root} chain at or before now")
        val maxAgeMs = root.maxQuoteAgeMinutes * MS_PER_MINUTE
        val plan = StructurePlanner.plan(specs, snapshot, options.listings(root.root), maxAgeMs, root.contractSize)
        val ready =
            when (plan) {
                is StructurePlan.Refused -> return Signal.Suppressed(root.root, "${action.alias}: ${plan.reason}")
                is StructurePlan.Ready -> plan
            }
        val raw =
            size(ec, ready.maxLossPerUnit)
                ?: return Signal.Suppressed(root.root, "${action.alias}: no size (unbounded loss or undefined sizing)")
        val qty = raw.divide(root.volumeStep, 0, RoundingMode.DOWN).multiply(root.volumeStep)
        if (qty <
            root.volumeMin
        ) {
            return Signal.Suppressed(
                root.root,
                "${action.alias}: size $raw is below the venue minimum ${root.volumeMin}",
            )
        }
        val structureId = "${action.alias}-${ids.next()}"
        val legs =
            ready.legs.map { leg ->
                OrderRequest.Market(
                    id = ids.next(),
                    symbol = "${root.venue}:${OptionSymbols.qktCode(leg.contract)}",
                    side = leg.side,
                    quantity = qty,
                    timeInForce = TimeInForce.GTC,
                    timestamp = now,
                    strategyId = ec.strategyContext.strategyId,
                )
            }
        return Signal.SubmitGroup(structureId, legs)
    }

    private fun view(instruments: InstrumentRegistry): ChainView =
        views?.takeIf { it.first === instruments }?.second ?: ChainView(instruments).also { views = instruments to it }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
