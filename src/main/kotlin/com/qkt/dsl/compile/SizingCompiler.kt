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
        streamAlias: String,
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
                    val cs = contractSizeFor(ec, streamAlias)
                    usd.divide(entry.multiply(cs, Money.CONTEXT), Money.CONTEXT)
                }
            }
            is SizeRiskAbs -> {
                require(stopDistance != null && stopDistance.signum() > 0) {
                    "SIZING RISK \$ requires a resolvable stop distance via BRACKET STOP LOSS"
                }
                val e = exprCompiler.compile(sizing.usd)
                CompiledSize { ec, _ ->
                    val amount = (e.evaluate(ec) as Value.Num).v
                    val cs = contractSizeFor(ec, streamAlias)
                    amount.divide(stopDistance.multiply(cs, Money.CONTEXT), Money.CONTEXT)
                }
            }
            is SizePositionFull -> {
                CompiledSize { ec, _ ->
                    val symbol =
                        ec.streams[sizing.stream]?.qktSymbol
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
                    val cs = contractSizeFor(ec, streamAlias)
                    equity.multiply(frac, Money.CONTEXT).divide(entry.multiply(cs, Money.CONTEXT), Money.CONTEXT)
                }
            }
            is SizePctBalance -> {
                val e = exprCompiler.compile(sizing.frac)
                CompiledSize { ec, entry ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val balance = ec.strategyContext.pnl.balance()
                    val cs = contractSizeFor(ec, streamAlias)
                    balance.multiply(frac, Money.CONTEXT).divide(entry.multiply(cs, Money.CONTEXT), Money.CONTEXT)
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
                    val cs = contractSizeFor(ec, streamAlias)
                    equity
                        .multiply(frac, Money.CONTEXT)
                        .divide(stopDistance.multiply(cs, Money.CONTEXT), Money.CONTEXT)
                }
            }
        }

    /**
     * The instrument contract size for the action's stream, e.g. 100 for XAUUSD
     * (1 lot = 100 oz) or 100,000 for FX majors (1 lot = 100,000 base units).
     * Money-amount sizing must divide through it so the result is broker lots,
     * not base-asset units: $10,000 of XAUUSD at $2,000 is 0.05 lots, not 5.
     */
    private fun contractSizeFor(
        ec: EvalContext,
        streamAlias: String,
    ): BigDecimal {
        val qktSymbol =
            ec.streams[streamAlias]?.qktSymbol
                ?: error("Unknown stream alias: $streamAlias")
        return ec.strategyContext.instruments
            .require(qktSymbol)
            .contractSize
    }
}
