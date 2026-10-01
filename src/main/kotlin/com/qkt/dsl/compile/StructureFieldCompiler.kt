package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.derivatives.options.ExpiringLeg
import com.qkt.derivatives.options.OptionLeg
import com.qkt.derivatives.options.StructureRisk
import com.qkt.derivatives.options.chain.HeldLeg
import com.qkt.derivatives.options.chain.PositionGreeks
import com.qkt.derivatives.options.chain.StructureGreeks
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.instrument.OptionTerms
import com.qkt.strategy.StructurePosition
import com.qkt.strategy.StructureState
import java.math.BigDecimal

/** The `POSITION.<structure>.<field>` sources that only a structure alias has. */
internal val STRUCTURE_SOURCES =
    setOf(
        StateSource.STRUCTURE_DELTA,
        StateSource.STRUCTURE_GAMMA,
        StateSource.STRUCTURE_VEGA,
        StateSource.STRUCTURE_THETA,
        StateSource.STRUCTURE_DTE,
        StateSource.STRUCTURE_CREDIT,
        StateSource.STRUCTURE_MAX_LOSS,
        StateSource.STRUCTURE_PNL_PCT,
    )

/** The DSL spelling of [source] as a `POSITION` accessor. */
internal fun accessorName(source: StateSource): String =
    DslVocabulary.positionAccessors.entries
        .first {
            it.value ==
                source
        }.key

/**
 * Compiles `POSITION.<structure>` (contracts per leg while the structure is live, else 0) and its
 * fields, read from the strategy's [com.qkt.strategy.StructureView]. A field is `Undefined` unless the
 * structure is OPEN:
 * - `credit`: the opening premium received (Σ −entry quantity × contract size × entry price), negative for a debit;
 * - `max_loss`: the worst expiry loss from opening ([StructureRisk] at entry prices), `Undefined` when unbounded;
 * - `pnl`: premium P&L before fees, realized plus held legs at their marks; `pnl_pct`: 100 × pnl ÷ |credit|;
 * - `dte`: fractional days to the nearest expiry of a held leg;
 * - `delta`, `gamma`, `vega`, `theta`: [StructureGreeks] of the held legs from the latest snapshot at or
 *   before the clock.
 */
internal class StructureFieldCompiler(
    private val support: StructureSupport,
) {
    /** `POSITION.<alias>`: the live structure's size, else 0. */
    fun size(alias: String): CompiledExpr =
        CompiledExpr { ctx ->
            Value.Num(
                ctx.strategyContext.structures
                    .live(alias)
                    ?.size ?: BigDecimal.ZERO,
            )
        }

    /** `POSITION.<alias>.<field>` for a structure alias. */
    fun field(ref: StateAccessor): CompiledExpr {
        val read: (EvalContext, StructurePosition) -> BigDecimal? =
            when (ref.source) {
                StateSource.POSITION_PNL -> ::pnl
                StateSource.STRUCTURE_PNL_PCT -> { ctx, s -> pnlPct(ctx, s) }
                StateSource.STRUCTURE_CREDIT -> { _, s -> credit(s) }
                StateSource.STRUCTURE_MAX_LOSS -> ::maxLoss
                StateSource.STRUCTURE_DTE -> ::dte
                StateSource.STRUCTURE_DELTA -> greek(PositionGreeks::delta)
                StateSource.STRUCTURE_GAMMA -> greek(PositionGreeks::gamma)
                StateSource.STRUCTURE_VEGA -> greek(PositionGreeks::vega)
                StateSource.STRUCTURE_THETA -> greek(PositionGreeks::theta)
                else -> throw CompileError(
                    "POSITION.${ref.key}.${accessorName(ref.source)} is not a structure field; a structure has " +
                        "qty, delta, gamma, vega, theta, dte, credit, max_loss, pnl and pnl_pct",
                )
            }
        return CompiledExpr { ctx ->
            val live =
                ctx.strategyContext.structures
                    .live(ref.key)
                    ?.takeIf { it.state == StructureState.OPEN }
            live?.let { read(ctx, it) }?.let(Value::Num) ?: Value.Undefined
        }
    }

    private fun credit(s: StructurePosition): BigDecimal =
        s.legs.fold(BigDecimal.ZERO) { sum, leg ->
            sum.subtract(leg.entryQuantity.multiply(leg.contractSize).multiply(requireNotNull(leg.entryPrice)))
        }

    private fun pnl(
        ctx: EvalContext,
        s: StructurePosition,
    ): BigDecimal? {
        var total = BigDecimal.ZERO
        for (leg in s.legs) {
            total = total.add(leg.realized)
            if (leg.heldQuantity.signum() == 0) continue
            val mark = ctx.strategyContext.structures.mark(leg.symbol) ?: return null
            val move = mark.subtract(requireNotNull(leg.entryPrice))
            total = total.add(leg.heldQuantity.multiply(leg.contractSize).multiply(move))
        }
        return total
    }

    private fun pnlPct(
        ctx: EvalContext,
        s: StructurePosition,
    ): BigDecimal? {
        val credit = credit(s).abs()
        if (credit.signum() == 0) return null
        return pnl(ctx, s)?.multiply(HUNDRED)?.divide(credit, Money.CONTEXT)
    }

    private fun maxLoss(
        ctx: EvalContext,
        s: StructurePosition,
    ): BigDecimal? =
        StructureRisk.maxLoss(
            s.legs.map { leg ->
                val terms = terms(ctx, leg.symbol)
                val held = OptionLeg(terms.right, terms.strike, leg.entryQuantity, leg.contractSize)
                ExpiringLeg(held, leg.expiryMs, requireNotNull(leg.entryPrice))
            },
        )

    private fun dte(
        ctx: EvalContext,
        s: StructurePosition,
    ): BigDecimal? {
        val now = ctx.strategyContext.clock.now()
        val nearest = s.legs.filter { it.heldQuantity.signum() != 0 && it.expiryMs > now }.minOfOrNull { it.expiryMs }
        return nearest?.let { BigDecimal.valueOf(it - now).divide(DAY_MS, Money.CONTEXT) }
    }

    private fun greek(pick: (PositionGreeks) -> Double): (EvalContext, StructurePosition) -> BigDecimal? =
        { ctx, s -> greeks(ctx, s)?.let { BigDecimal.valueOf(pick(it)) } }

    private fun greeks(
        ctx: EvalContext,
        s: StructurePosition,
    ): PositionGreeks? {
        val now = ctx.strategyContext.clock.now()
        val held = s.legs.filter { it.heldQuantity.signum() != 0 && it.expiryMs > now }
        val options = ctx.strategyContext.instruments.options() ?: return null
        val root = held.firstOrNull()?.let { options.optionRoot(it.symbol) } ?: return null
        val snapshot = support.view(ctx.strategyContext.instruments).latest(root.root, now) ?: return null
        val legs = held.map { HeldLeg(requireNotNull(options.venueName(it.symbol)), it.heldQuantity, it.contractSize) }
        return StructureGreeks.of(
            legs,
            snapshot,
            options.listings(root.root),
            root.maxQuoteAgeMinutes * MS_PER_MINUTE,
            now,
        )
    }

    private fun terms(
        ctx: EvalContext,
        symbol: String,
    ): OptionTerms =
        requireNotNull(
            ctx.strategyContext.instruments
                .lookup(symbol)
                ?.derivative as? OptionTerms,
        ) {
            "$symbol has no option terms"
        }

    private companion object {
        val HUNDRED = BigDecimal(100)
        val DAY_MS = BigDecimal(86_400_000)
        const val MS_PER_MINUTE = 60_000L
    }
}
