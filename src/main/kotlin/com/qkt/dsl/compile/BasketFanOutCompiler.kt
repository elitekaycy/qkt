package com.qkt.dsl.compile

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.SizeNotional
import com.qkt.strategy.Signal
import java.math.BigDecimal

/** Compiles a `BUY`/`SELL` on a basket alias into one equal-notional market order per constituent. */
internal class BasketFanOutCompiler(
    private val exprCompiler: ExprCompiler,
) {
    /**
     * Fan a `BUY`/`SELL` on a basket alias out to one plain-market order per constituent,
     * each sized so its notional is `total / N` — equal economic weight, not equal lots, since
     * one lot of two differently-priced symbols is two different exposures. The basket has no
     * tradeable symbol of its own, so there is no single order to emit; each constituent order
     * routes by its own `qktSymbol`. e.g. `BUY antipodean SIZING NOTIONAL 10000` over
     * `[aud, nzd]` emits a BUY of $5,000 notional on each.
     *
     * Basket orders are plain market in v1: a BRACKET/OCO/TIF/STACK or a LIMIT/STOP type on a
     * basket order is a compile error, and the sizing must be `SIZING NOTIONAL` — the only mode
     * that yields a single economic total to split across constituents priced differently.
     */
    fun compile(
        basketAlias: String,
        constituents: List<String>,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        val plain = "basket orders are plain market in v1"
        require(opts.bracket == null) { "BASKET order on '$basketAlias' cannot carry a BRACKET ($plain)." }
        require(opts.oco == null) { "BASKET order on '$basketAlias' cannot carry an OCO ($plain)." }
        require(opts.stack == null && opts.stackAts.isEmpty()) {
            "BASKET order on '$basketAlias' cannot carry STACK/STACK_AT ($plain)."
        }
        require(opts.tif == null) { "BASKET order on '$basketAlias' cannot carry a TIF ($plain)." }
        require(opts.orderType == null || opts.orderType == Market) {
            "BASKET order on '$basketAlias' must be a market order; LIMIT/STOP are not supported in v1."
        }
        val sizing = opts.sizing
        require(sizing is SizeNotional) {
            "BASKET order on '$basketAlias' requires notional sizing (SIZING <amount> USD); got " +
                "${sizing?.let { it::class.simpleName } ?: "no SIZING"} — each constituent is sized " +
                "to an equal share of the notional."
        }
        val notionalExpr = exprCompiler.compile(sizing.usd)
        val n = BigDecimal(constituents.size)
        return { ctx ->
            val total = notionalExpr.evaluate(ctx)
            require(total is Value.Num) { "BASKET SIZING NOTIONAL must be numeric, got $total" }
            val perConstituent = total.v.divide(n, Money.CONTEXT)
            constituents.map { alias ->
                val key = ctx.streams[alias] ?: error("Unknown basket constituent alias: $alias")
                val symbol = key.qktSymbol
                val price =
                    ctx.hub.latest(key)?.close
                        ?: error("BASKET '$basketAlias' constituent '$alias' has no price yet")
                val contractSize =
                    ctx.strategyContext.instruments
                        .require(symbol)
                        .contractSize
                val qty = perConstituent.divide(price.multiply(contractSize, Money.CONTEXT), Money.CONTEXT)
                if (side == Side.BUY) Signal.Buy(symbol, qty) else Signal.Sell(symbol, qty)
            }
        }
    }
}
