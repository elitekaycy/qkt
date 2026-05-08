package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import java.math.BigDecimal

fun interface CompiledSize {
    fun evaluate(
        ec: EvalContext,
        entryPrice: BigDecimal,
    ): BigDecimal
}

class SizingCompiler(
    private val exprCompiler: ExprCompiler,
) {
    fun compile(
        sizing: SizingAst,
        stopDistance: BigDecimal?,
    ): CompiledSize =
        when (sizing) {
            is SizeQty -> {
                val e = exprCompiler.compile(sizing.expr)
                CompiledSize { ec, _ -> (e.evaluate(ec) as Value.Num).v }
            }
            is SizeNotional -> {
                val e = exprCompiler.compile(sizing.usd)
                CompiledSize { ec, entry ->
                    val usd = (e.evaluate(ec) as Value.Num).v
                    usd.divide(entry, Money.CONTEXT)
                }
            }
            is SizeRiskAbs -> {
                require(stopDistance != null && stopDistance.signum() > 0) {
                    "SIZING RISK \$ requires a resolvable stop distance via BRACKET STOP LOSS"
                }
                val e = exprCompiler.compile(sizing.usd)
                CompiledSize { ec, _ ->
                    val amount = (e.evaluate(ec) as Value.Num).v
                    amount.divide(stopDistance, Money.CONTEXT)
                }
            }
            is SizePositionFull -> {
                CompiledSize { ec, _ ->
                    val symbol =
                        ec.streams[sizing.stream]?.symbol
                            ?: error("Unknown stream alias: ${sizing.stream}")
                    ec.strategyContext.positions
                        .positionFor(symbol)
                        ?.quantity
                        ?.abs()
                        ?: BigDecimal.ZERO
                }
            }
            is SizePctEquity -> {
                val e = exprCompiler.compile(sizing.frac)
                CompiledSize { ec, entry ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val equity = ec.strategyContext.pnl.equity()
                    equity.multiply(frac, Money.CONTEXT).divide(entry, Money.CONTEXT)
                }
            }
            is SizePctBalance -> {
                val e = exprCompiler.compile(sizing.frac)
                CompiledSize { ec, entry ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val balance = ec.strategyContext.pnl.balance()
                    balance.multiply(frac, Money.CONTEXT).divide(entry, Money.CONTEXT)
                }
            }
            is SizeRiskFrac -> {
                require(stopDistance != null && stopDistance.signum() > 0) {
                    "SIZING RISK <fraction> requires a resolvable stop distance via BRACKET STOP LOSS"
                }
                val e = exprCompiler.compile(sizing.frac)
                CompiledSize { ec, _ ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val equity = ec.strategyContext.pnl.equity()
                    equity.multiply(frac, Money.CONTEXT).divide(stopDistance, Money.CONTEXT)
                }
            }
        }
}
