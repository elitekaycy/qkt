package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import java.math.BigDecimal

/** Evaluates an order quantity from the current strategy state and resolved entry geometry. */
fun interface CompiledSize {
    /**
     * Returns the broker quantity. [runtimeStopDistance] is supplied by bracket actions whose
     * stop price depends on runtime expressions; non-risk sizing ignores it.
     */
    fun evaluate(
        ec: EvalContext,
        entryPrice: BigDecimal,
        runtimeStopDistance: BigDecimal?,
    ): BigDecimal
}

/** Evaluates sizing where no runtime-resolved stop distance is available. */
fun CompiledSize.evaluate(
    ec: EvalContext,
    entryPrice: BigDecimal,
): BigDecimal = evaluate(ec, entryPrice, runtimeStopDistance = null)

/** Compiles DSL sizing expressions into deterministic runtime quantity evaluators. */
class SizingCompiler(
    private val exprCompiler: ExprCompiler,
) {
    /**
     * True once any sizing compiled through this instance was `RISK … OF BOOK`. Deploy
     * paths read it to fail closed when no book is bound (a standalone deploy has none).
     */
    var compiledBookSizing: Boolean = false
        private set

    /**
     * Compiles [sizing]. Risk-based forms require either a positive [stopDistance] known now or
     * [runtimeStopDistanceAvailable] when a bracket will provide the resolved distance later.
     */
    fun compile(
        sizing: SizingAst,
        stopDistance: BigDecimal?,
        streamAlias: String,
        runtimeStopDistanceAvailable: Boolean = false,
    ): CompiledSize =
        when (sizing) {
            is SizeQty -> {
                val e = exprCompiler.compile(sizing.expr)
                CompiledSize { ec, _, _ -> (e.evaluate(ec) as Value.Num).v }
            }
            is SizeNotional -> {
                val e = exprCompiler.compile(sizing.usd)
                CompiledSize { ec, entry, _ ->
                    val usd = (e.evaluate(ec) as Value.Num).v
                    usd.divide(accountValuePerLot(ec, streamAlias, entry, entry), Money.CONTEXT)
                }
            }
            is SizeRiskAbs -> {
                val staticStopDistance = stopDistance?.takeIf { it.signum() > 0 }
                require(staticStopDistance != null || runtimeStopDistanceAvailable) {
                    "SIZING RISK \$ requires a resolvable stop distance via BRACKET STOP LOSS"
                }
                val e = exprCompiler.compile(sizing.usd)
                CompiledSize { ec, entry, runtimeStopDistance ->
                    val amount = (e.evaluate(ec) as Value.Num).v
                    val resolvedStopDistance = resolveStopDistance(staticStopDistance, runtimeStopDistance)
                    amount.divide(accountValuePerLot(ec, streamAlias, resolvedStopDistance, entry), Money.CONTEXT)
                }
            }
            is SizePositionFull -> {
                CompiledSize { ec, _, _ ->
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
                CompiledSize { ec, entry, _ ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val equity = ec.strategyContext.pnl.equity()
                    equity
                        .multiply(frac, Money.CONTEXT)
                        .divide(accountValuePerLot(ec, streamAlias, entry, entry), Money.CONTEXT)
                }
            }
            is SizePctBalance -> {
                val e = exprCompiler.compile(sizing.frac)
                CompiledSize { ec, entry, _ ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val balance = ec.strategyContext.pnl.balance()
                    balance
                        .multiply(frac, Money.CONTEXT)
                        .divide(accountValuePerLot(ec, streamAlias, entry, entry), Money.CONTEXT)
                }
            }
            is SizeRiskFrac -> {
                val staticStopDistance = stopDistance?.takeIf { it.signum() > 0 }
                require(staticStopDistance != null || runtimeStopDistanceAvailable) {
                    "SIZING RISK <fraction> requires a resolvable stop distance via BRACKET STOP LOSS"
                }
                val e = exprCompiler.compile(sizing.frac)
                CompiledSize { ec, entry, runtimeStopDistance ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val equity = ec.strategyContext.pnl.equity()
                    val resolvedStopDistance = resolveStopDistance(staticStopDistance, runtimeStopDistance)
                    equity
                        .multiply(frac, Money.CONTEXT)
                        .divide(accountValuePerLot(ec, streamAlias, resolvedStopDistance, entry), Money.CONTEXT)
                }
            }
            is SizeRiskFracOfBook -> {
                compiledBookSizing = true
                val staticStopDistance = stopDistance?.takeIf { it.signum() > 0 }
                require(staticStopDistance != null || runtimeStopDistanceAvailable) {
                    "SIZING RISK <fraction> OF BOOK requires a resolvable stop distance via BRACKET STOP LOSS"
                }
                val e = exprCompiler.compile(sizing.frac)
                CompiledSize { ec, entry, runtimeStopDistance ->
                    val frac = (e.evaluate(ec) as Value.Num).v
                    val book =
                        ec.strategyContext.book
                            ?: error(
                                "SIZING RISK OF BOOK requires a portfolio deploy with CAPITAL; " +
                                    "no book is bound for this strategy",
                            )
                    val resolvedStopDistance = resolveStopDistance(staticStopDistance, runtimeStopDistance)
                    book
                        .balance()
                        .multiply(frac, Money.CONTEXT)
                        .divide(accountValuePerLot(ec, streamAlias, resolvedStopDistance, entry), Money.CONTEXT)
                }
            }
        }

    private fun resolveStopDistance(
        staticStopDistance: BigDecimal?,
        runtimeStopDistance: BigDecimal?,
    ): BigDecimal {
        val resolved = runtimeStopDistance ?: staticStopDistance
        require(resolved != null && resolved.signum() > 0) {
            "risk sizing requires a positive runtime stop distance"
        }
        return resolved
    }

    /**
     * Converts a stream's native quote-currency value per unit into account-currency
     * value per broker lot by applying its contract size and the point-in-time FX rate.
     */
    private fun accountValuePerLot(
        ec: EvalContext,
        streamAlias: String,
        nativeValuePerUnit: BigDecimal,
        referencePrice: BigDecimal,
    ): BigDecimal {
        val qktSymbol =
            ec.streams[streamAlias]?.qktSymbol
                ?: error("Unknown stream alias: $streamAlias")
        val contractSize =
            ec.strategyContext.instruments
                .require(qktSymbol)
                .contractSize
        val quoteToAccountRate =
            ec.strategyContext.quoteToAccountRate
                .rate(qktSymbol, ec.strategyContext.clock.now(), referencePrice)
        require(quoteToAccountRate.signum() > 0) {
            "quote-to-account rate must be positive for $qktSymbol: $quoteToAccountRate"
        }
        return nativeValuePerUnit
            .multiply(contractSize, Money.CONTEXT)
            .multiply(quoteToAccountRate, Money.CONTEXT)
    }
}
