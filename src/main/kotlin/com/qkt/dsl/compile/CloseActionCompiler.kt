package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Compiles `CLOSE <stream>` and `CLOSE_ALL`: cancel the symbol's pending orders, then flatten
 * its position leg by leg (by ticket), or a basket's constituents one by one.
 */
internal class CloseActionCompiler(
    private val ids: IdGenerator,
    private val baskets: Map<String, List<String>>,
) {
    fun compileCloseAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            val out = mutableListOf<Signal>()
            for (streamAlias in ctx.streams.keys) {
                val sym = ctx.streams[streamAlias]?.qktSymbol ?: continue
                out.add(Signal.CancelPendingForSymbol(sym))
            }
            val open = ctx.strategyContext.positions.allPositions()
            for (symbol in open.keys) {
                out.addAll(closeSignalsFor(ctx, symbol))
            }
            out
        }

    fun compileClose(streamAlias: String): (EvalContext) -> List<Signal> {
        baskets[streamAlias]?.let { constituents ->
            // CLOSE on a basket flattens every constituent — one basket close, N real closes.
            return { ctx ->
                val signals = mutableListOf<Signal>()
                for (alias in constituents) {
                    val symbol = ctx.streams[alias]?.qktSymbol ?: error("Unknown basket constituent alias: $alias")
                    signals.add(Signal.CancelPendingForSymbol(symbol))
                    signals.addAll(closeSignalsFor(ctx, symbol))
                }
                signals
            }
        }
        return { ctx ->
            val symbol = ctx.streams[streamAlias]?.qktSymbol ?: error("Unknown stream alias: $streamAlias")
            val signals = mutableListOf<Signal>()
            signals.add(Signal.CancelPendingForSymbol(symbol))
            signals.addAll(closeSignalsFor(ctx, symbol))
            signals
        }
    }

    /**
     * Signals that flatten [symbol]. When the position is held as independent legs (e.g. a
     * filled straddle), each leg is closed individually and attributed to its leg id — so a
     * net-zero pair still closes both sides, and on a hedging venue each close targets the
     * exact ticket instead of opening a counter. A PRIMARY leg is likewise closed by ticket;
     * this is required on hedging venues and remains valid on netting venues. The net-quantity
     * fallback exists only for legacy position views without leg metadata.
     */
    private fun closeSignalsFor(
        ctx: EvalContext,
        symbol: String,
    ): List<Signal> {
        val legs = ctx.strategyContext.positions.legsFor(symbol)
        if (legs.any { it.role == com.qkt.positions.LegRole.INDEPENDENT }) {
            return legs.map { leg ->
                val exitSide = if (leg.side == Side.BUY) Side.SELL else Side.BUY
                Signal.Submit(
                    OrderRequest.Market(
                        id = ids.next(),
                        symbol = symbol,
                        side = exitSide,
                        quantity = leg.quantity,
                        timeInForce = TimeInForce.GTC,
                        timestamp = ctx.strategyContext.clock.now(),
                        closesTicket = leg.brokerTicket,
                        closesLegId = leg.legId,
                    ),
                )
            }
        }
        val primary = legs.firstOrNull { it.role == com.qkt.positions.LegRole.PRIMARY }
        if (primary != null) {
            val exitSide = if (primary.side == Side.BUY) Side.SELL else Side.BUY
            return listOf(
                Signal.Submit(
                    OrderRequest.Market(
                        id = ids.next(),
                        symbol = symbol,
                        side = exitSide,
                        quantity = primary.quantity,
                        timeInForce = TimeInForce.GTC,
                        timestamp = ctx.strategyContext.clock.now(),
                        closesTicket = primary.brokerTicket,
                        closesLegId = primary.legId,
                    ),
                ),
            )
        }
        val qty =
            ctx.strategyContext.positions
                .positionFor(symbol)
                ?.quantity ?: BigDecimal.ZERO
        return when {
            qty.signum() > 0 -> listOf(Signal.Sell(symbol, qty))
            qty.signum() < 0 -> listOf(Signal.Buy(symbol, qty.abs()))
            else -> emptyList()
        }
    }
}
