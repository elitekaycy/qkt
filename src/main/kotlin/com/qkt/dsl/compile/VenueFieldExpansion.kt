package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.depth.BookDepthSymbol
import com.qkt.marketdata.openinterest.OpenInterestSymbol

/**
 * The walk the venue-series fields share ([OpenInterestFieldExpansion], [BookDepthFieldExpansion]): every
 * `<alias>.<field>` on a venue stream for which [hidden] names a stream becomes a read of that hidden stream's
 * close, the stream added beside the alias (whose name is `<alias>/<field>`), with the alias's timeframe and
 * warmup. Only a venue stream has a venue's series; the alias itself is kept, since it still trades. Applying
 * it twice is a no-op, and a strategy that reads no such field is returned unchanged.
 */
internal object VenueFieldExpansion {
    private val NOT_VENUES =
        setOf("HUB", "CHAIN", "OPTIONS", "MACRO", "SERIES", "BASKET", OpenInterestSymbol.BROKER, BookDepthSymbol.BROKER)

    /** [ast] with the fields [hidden] answers for rewritten; [hidden] gets the field and the alias's stream. */
    fun apply(
        ast: StrategyAst,
        hidden: (field: String, venue: StreamDecl) -> Pair<String, String>?,
    ): StrategyAst {
        val venues = ast.streams.filter { it.broker.uppercase() !in NOT_VENUES }.associateBy { it.alias }
        val added = LinkedHashMap<String, StreamDecl>()
        val transform =
            ExprTransform(
                onRef = { it },
                onStreamField = { ref ->
                    val decl = venues[ref.stream]
                    val target = decl?.let { hidden(ref.field, it) }
                    if (decl == null || target == null) {
                        ref
                    } else {
                        val alias = HubFieldExpansion.hiddenAlias(ref.stream, ref.field)
                        val (broker, symbol) = target
                        added.getOrPut(alias) { StreamDecl(alias, broker, symbol, decl.timeframe, decl.warmupBars) }
                        StreamFieldRef(alias, "close")
                    }
                },
            )
        val rewritten = rewriteExprs(ast, transform)
        if (added.isEmpty()) return ast
        return rewritten.copy(streams = rewritten.streams.filter { it.alias !in added } + added.values)
    }
}
