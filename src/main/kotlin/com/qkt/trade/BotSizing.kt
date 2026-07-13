package com.qkt.trade

import com.qkt.common.Money
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Resolves a DSL sizing form into raw broker lots for a one-shot trade, then
 * quantizes to the instrument's volume constraints. Percent/risk/notional forms
 * convert account currency into lots via `price x contractSize`, which is only
 * valid when the instrument's quote currency equals the account currency — there
 * is no point-in-time FX service one-shot, so mismatches reject.
 */
internal fun resolveBotLots(
    sizing: SizingAst?,
    ctx: BotQuoteContext,
    entryPrice: BigDecimal,
    stopDistance: BigDecimal?,
): BigDecimal {
    val lots =
        when (sizing) {
            null -> error("bot order requires a SIZING clause")
            is SizeQty -> literal(sizing.expr, "SIZING")
            is SizeNotional ->
                literal(sizing.usd, "SIZING <n> USD")
                    .divide(valuePerLot(ctx, entryPrice), Money.CONTEXT)
            is SizePctEquity ->
                account(ctx.equity, "equity")
                    .multiply(literal(sizing.frac, "% OF EQUITY"), Money.CONTEXT)
                    .divide(valuePerLot(ctx, entryPrice), Money.CONTEXT)
            is SizePctBalance ->
                account(ctx.balance, "balance")
                    .multiply(literal(sizing.frac, "% OF BALANCE"), Money.CONTEXT)
                    .divide(valuePerLot(ctx, entryPrice), Money.CONTEXT)
            is SizeRiskFrac ->
                account(ctx.equity, "equity")
                    .multiply(literal(sizing.frac, "RISK"), Money.CONTEXT)
                    .divide(valuePerLot(ctx, riskDistance(stopDistance)), Money.CONTEXT)
            is SizeRiskAbs ->
                literal(sizing.usd, "RISK $")
                    .divide(valuePerLot(ctx, riskDistance(stopDistance)), Money.CONTEXT)
            else ->
                error(
                    "sizing form ${sizing::class.simpleName} is not supported one-shot; " +
                        "use lots, % OF EQUITY/BALANCE, RISK, or USD notional",
                )
        }
    return quantizeLots(lots, ctx)
}

internal fun quantizeLots(
    lots: BigDecimal,
    ctx: BotQuoteContext,
): BigDecimal {
    var q = lots
    ctx.volumeStep?.takeIf { it.signum() > 0 }?.let { step ->
        q = q.divide(step, 0, RoundingMode.FLOOR).multiply(step)
    }
    ctx.volumeMin?.let {
        require(q >= it) { "volume $q is below the instrument minimum $it" }
    }
    ctx.volumeMax?.let {
        require(q <= it) { "volume $q exceeds the instrument maximum $it" }
    }
    require(q.signum() > 0) { "resolved volume must be > 0: $q" }
    return q
}

private fun valuePerLot(
    ctx: BotQuoteContext,
    nativeValuePerUnit: BigDecimal,
): BigDecimal {
    val quote = ctx.quoteCurrency
    require(quote != null && quote.equals(ctx.accountCurrency, ignoreCase = true)) {
        "percent/risk/notional sizing needs quote currency (${ctx.quoteCurrency}) to equal " +
            "account currency (${ctx.accountCurrency}) one-shot; pass explicit lots instead"
    }
    val contractSize =
        ctx.contractSize
            ?: error("venue did not report a contract size; pass explicit lots instead")
    return nativeValuePerUnit.multiply(contractSize, Money.CONTEXT)
}

private fun riskDistance(stopDistance: BigDecimal?): BigDecimal {
    require(stopDistance != null && stopDistance.signum() > 0) {
        "risk sizing requires a stop loss (--sl) to derive the risk distance"
    }
    return stopDistance
}

private fun account(
    value: BigDecimal?,
    label: String,
): BigDecimal = value ?: error("venue did not report account $label; pass explicit lots instead")

internal fun literal(
    expr: ExprAst,
    where: String,
): BigDecimal =
    (expr as? NumLit)?.value
        ?: error("$where must be a numeric literal one-shot, got ${expr::class.simpleName}")
