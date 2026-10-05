package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.openinterest.OpenInterestSymbol

/**
 * Rewrites `<alias>.open_interest` into a hidden stream of its own, the way a hub field is
 * ([HubFieldExpansion]):
 *
 *     perp = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m      WHEN perp.open_interest > 1000 ...
 *
 * becomes, beside `perp`,
 *
 *     perp/open_interest = OI:DERIBIT:BTC_USDC_PERPETUAL EVERY 1m      WHEN perp/open_interest.close > 1000 ...
 *
 * The hidden stream carries one observation per published figure, at the instant the venue made it known
 * ([OpenInterestSymbol]), so indicators, lookback and warmup work on open interest as on a price, and the
 * backtest and live feeds route it like any other stream: from the stored series, or from the account's
 * gateway. Only a venue stream has open interest; the alias itself is kept, since it still trades.
 * Applying the pass twice is a no-op. A strategy that never reads the field is returned unchanged.
 */
object OpenInterestFieldExpansion {
    private val NOT_VENUES = setOf("HUB", "CHAIN", "OPTIONS", "MACRO", "SERIES", "BASKET", OpenInterestSymbol.BROKER)

    fun apply(ast: StrategyAst): StrategyAst {
        val venues = ast.streams.filter { it.broker.uppercase() !in NOT_VENUES }.associateBy { it.alias }
        val hidden = LinkedHashMap<String, StreamDecl>()
        val transform =
            ExprTransform(
                onRef = { it },
                onStreamField = { ref ->
                    val decl = venues[ref.stream]
                    if (decl == null || ref.field != OpenInterestSymbol.FIELD) {
                        ref
                    } else {
                        val alias = HubFieldExpansion.hiddenAlias(ref.stream, ref.field)
                        hidden.getOrPut(alias) {
                            StreamDecl(
                                alias,
                                OpenInterestSymbol.BROKER,
                                decl.qktSymbol,
                                decl.timeframe,
                                decl.warmupBars,
                            )
                        }
                        StreamFieldRef(alias, "close")
                    }
                },
            )
        val rewritten = rewriteExprs(ast, transform)
        if (hidden.isEmpty()) return ast
        return rewritten.copy(streams = rewritten.streams.filter { it.alias !in hidden } + hidden.values)
    }
}
