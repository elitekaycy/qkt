package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.Resize
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import java.math.BigDecimal
import java.math.RoundingMode

/** Compiles `RESIZE <stream> TO <sizing>`, which moves the primary leg to a per-bar target size. */
internal class ResizeActionCompiler(
    private val exprCompiler: ExprCompiler,
    private val sizingCompiler: SizingCompiler,
    private val ids: IdGenerator,
) {
    /** Default resize deadband: skip a resize whose `|target - current|` is under 5% of target. */
    private val defaultMinStepFraction = BigDecimal("0.05")

    /**
     * Set the symbol's PRIMARY leg to a per-bar target magnitude, trimming or adding to reach it.
     * Reuses the SIZING grammar for the target (no stop distance, so RISK sizing self-rejects).
     * Grows with a same-side market add (averages into the primary); shrinks with a partial
     * close-by-ticket of the primary (`TO 0` closes it fully). A no-op when there is no primary
     * or when `|target - current|` is below the [Resize.minStep] deadband (default 5% of target).
     */
    fun compile(action: Resize): (EvalContext) -> List<Signal> {
        val compiledTarget = sizingCompiler.compile(action.target, stopDistance = null, streamAlias = action.stream)
        val compiledMinStep = action.minStep?.let { exprCompiler.compile(it) }
        return resize@{ ctx ->
            val symbol =
                ctx.streams[action.stream]?.qktSymbol
                    ?: error("Unknown stream alias: ${action.stream}")
            val primary =
                ctx.strategyContext.positions
                    .legsFor(symbol)
                    .firstOrNull { it.role == com.qkt.positions.LegRole.PRIMARY }
                    ?: return@resize emptyList()
            val refPrice = ctx.candle.close
            val rawTarget = compiledTarget.evaluate(ctx, refPrice)
            val target = if (rawTarget.signum() < 0) BigDecimal.ZERO else rawTarget
            val cur = primary.quantity.abs()
            val delta = target.subtract(cur, Money.CONTEXT)
            val instrument = ctx.strategyContext.instruments.lookup(symbol)
            val configuredMinStep =
                compiledMinStep?.let { (it.evaluate(ctx) as? Value.Num)?.v }
                    ?: target.multiply(defaultMinStepFraction, Money.CONTEXT)
            val minStep = configuredMinStep.max(instrument?.volumeMin ?: BigDecimal.ZERO)
            if (delta.signum() == 0 || delta.abs() < minStep) return@resize emptyList()
            val quantity =
                instrument
                    ?.volumeStep
                    ?.takeIf { it.signum() > 0 }
                    ?.let { step -> delta.abs().divide(step, 0, RoundingMode.DOWN).multiply(step) }
                    ?: delta.abs()
            if (quantity.signum() == 0 || quantity < (instrument?.volumeMin ?: BigDecimal.ZERO)) {
                return@resize listOf(
                    Signal.Suppressed(
                        symbol = symbol,
                        reason =
                            "RESIZE delta ${delta.abs().toPlainString()} quantized below venue minimum " +
                                "${(instrument?.volumeMin ?: BigDecimal.ZERO).toPlainString()}",
                    ),
                )
            }
            // Grow with a same-side add (the tracker averages it into the primary); shrink/flatten
            // by closing the PRIMARY's exact venue ticket. A plain opposite market would open a
            // counter-position on a hedging account instead of reducing exposure.
            val grow = delta.signum() > 0
            val side = if (grow == (primary.side == Side.BUY)) Side.BUY else Side.SELL
            listOf(
                Signal.Submit(
                    OrderRequest.Market(
                        id = ids.next(),
                        symbol = symbol,
                        side = side,
                        quantity = quantity,
                        timeInForce = TimeInForce.GTC,
                        timestamp = ctx.candle.endTime,
                        strategyId = ctx.strategyContext.strategyId,
                        closesTicket = if (grow) null else primary.brokerTicket,
                        closesLegId = if (grow) null else primary.legId,
                        partialClose = !grow && target.signum() > 0,
                    ),
                ),
            )
        }
    }
}
