package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import java.math.BigDecimal

private val MS_PER_SECOND = BigDecimal(1000)

/**
 * Compiles the `POSITION.<stream>.<field>` and `OPEN_ORDERS.<stream>` state accessors. Each
 * compiled closure resolves the stream alias to its symbol and reads the strategy's live
 * position, P&L, trade-history or open-order view for that symbol.
 */
internal object StateAccessorCompiler {
    fun compile(ref: StateAccessor): CompiledExpr =
        when (ref.source) {
            StateSource.POSITION_AVG_PRICE ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val position = ctx.strategyContext.positions.positionFor(symbol)
                    if (position == null || position.quantity.signum() == 0) {
                        Value.Undefined
                    } else {
                        Value.Num(position.avgEntryPrice)
                    }
                }
            // pnl = strategy-level realized + this-symbol unrealized.
            // Strategy-level realized isn't tracked per-symbol in StrategyPnL today;
            // a future enhancement could surface true per-symbol realized.
            StateSource.POSITION_PNL ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val realized = ctx.strategyContext.pnl.realized()
                    val unrealized = ctx.strategyContext.pnl.unrealizedFor(symbol)
                    Value.Num(realized.add(unrealized))
                }
            // Strategy-level realized P&L (not symbol-scoped). Per-symbol realized
            // requires lot-level accounting; see backlog.
            StateSource.POSITION_REALIZED_PNL ->
                CompiledExpr { ctx ->
                    Value.Num(ctx.strategyContext.pnl.realized())
                }
            StateSource.POSITION_UNREALIZED_PNL ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    Value.Num(ctx.strategyContext.pnl.unrealizedFor(symbol))
                }
            // holding_duration is SECONDS — the unit every shipped example and the DSL
            // reference assume. The clock is milliseconds internally; convert here so
            // `holding_duration > 7200` means "open for more than 2 hours".
            StateSource.POSITION_HOLDING_DURATION ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val openedAt =
                        ctx.strategyContext.positions
                            .positionFor(symbol)
                            ?.openedAt
                    val durationMs = if (openedAt == null) 0L else ctx.nowMs() - openedAt
                    Value.Num(BigDecimal.valueOf(durationMs).divide(MS_PER_SECOND, Money.CONTEXT))
                }
            StateSource.POSITION_MFE ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val mfe = ctx.strategyContext.positions.mfeFor(symbol) ?: BigDecimal.ZERO
                    Value.Num(mfe)
                }
            StateSource.POSITION_MAE ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val mae = ctx.strategyContext.positions.maeFor(symbol) ?: BigDecimal.ZERO
                    Value.Num(mae)
                }
            StateSource.POSITION_OPEN_COUNT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val n = ctx.strategyContext.positions.openCountFor(symbol)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_LONG_COUNT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val n = ctx.strategyContext.positions.longCountFor(symbol)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_SHORT_COUNT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val n = ctx.strategyContext.positions.shortCountFor(symbol)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_GROSS ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    Value.Num(ctx.strategyContext.positions.grossFor(symbol))
                }
            StateSource.POSITION_TRADES_TODAY ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val now = ctx.nowMs()
                    val n = ctx.strategyContext.tradeHistory.tradesTodayFor(symbol, now)
                    Value.Num(BigDecimal.valueOf(n.toLong()))
                }
            StateSource.POSITION_LAST_TRADE_AT ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    ctx.strategyContext.tradeHistory
                        .lastTradeAtFor(symbol)
                        ?.let { Value.Num(BigDecimal.valueOf(it)) } ?: Value.Undefined
                }
            StateSource.OPEN_ORDERS ->
                CompiledExpr { ctx ->
                    val symbol = ctx.streams[ref.key]?.qktSymbol ?: error("Unknown stream alias: ${ref.key}")
                    val count = ctx.strategyContext.openOrders.entryCountFor(symbol)
                    Value.Num(BigDecimal.valueOf(count.toLong()))
                }
            else -> throw IllegalArgumentException("StateAccessor source ${ref.source} is not supported")
        }
}
