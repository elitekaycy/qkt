package com.qkt.dsl.compile

import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenThen

/**
 * Rewrites a hub alias into one first-class stream per field the strategy actually reads.
 *
 * A hub dataset carries many fields; the engine's stream carries one value. Rather than teach
 * the candle hub, the indicator bindings, warmup and the live feed what a dataset is, this pass
 * makes each referenced field its own declared stream before anything else sees the AST:
 *
 *     cal = HUB:cal.high_impact.USD EVERY 1d        WHEN cal.surprise > 0 ...
 *
 * becomes
 *
 *     cal/surprise = HUB:cal.high_impact.USD/surprise EVERY 1d     WHEN cal/surprise.close > 0 ...
 *
 * Every downstream mechanism then works unchanged and for the right reasons: an indicator over
 * `cal.surprise` binds to the alias whose bars actually arrive, warmup counts that alias's own
 * closed candles, and a live session subscribes to exactly the field symbols it needs. The
 * earlier shortcut of resolving fields at evaluation time left indicators bound to an alias that
 * never received a bar, so they never updated -- this pass is what makes that impossible.
 *
 * The original dataset alias is removed from the declarations: it names something with no value
 * of its own, and leaving it would make the engine ask the store for a stream that cannot exist.
 * Orders against it are still refused by name, with the same read-only message a macro series gets.
 *
 * A strategy with no hub alias is returned unchanged.
 */
object HubFieldExpansion {
    /** The separator between a dataset alias and its field in a hidden alias. Not a DSL identifier character. */
    const val SEPARATOR: Char = '/'

    /** Fields that read instrument metadata rather than a value; a dataset has none, so they are left alone. */
    private val META_FIELDS: Set<String> = ExprCompiler.META_FIELDS

    data class Expanded(
        val ast: StrategyAst,
        /** The dataset aliases that were expanded away, for read-only enforcement by name. */
        val datasetAliases: Set<String>,
    )

    fun apply(ast: StrategyAst): Expanded {
        // Only dataset-level declarations expand. A field stream already carries `/` in its symbol,
        // so running this pass twice -- once at the parse boundary, once in the compiler for ASTs
        // built by hand -- is a no-op the second time rather than a second level of nesting.
        val hubDecls =
            ast.streams.filter { it.broker.equals(HUB_BROKER, ignoreCase = true) && SEPARATOR !in it.symbol }
        if (hubDecls.isEmpty()) return Expanded(ast, datasetAliasesOf(ast))

        // An order on a dataset alias is not rejected here: this pass also runs at the parse
        // boundary, where a bare exception has no position. The compiler refuses it by name
        // through `readOnlyAliases`, tagged with the rule that placed the order.
        val byAlias = hubDecls.associateBy { it.alias }
        val hidden = LinkedHashMap<String, StreamDecl>()
        val transform =
            ExprTransform(
                onRef = { it },
                onStreamField = { ref ->
                    val decl = byAlias[ref.stream]
                    if (decl == null || ref.field in META_FIELDS) {
                        ref
                    } else {
                        val alias = hiddenAlias(ref.stream, ref.field)
                        hidden.getOrPut(alias) {
                            StreamDecl(
                                alias = alias,
                                broker = decl.broker,
                                symbol = "${decl.symbol}$SEPARATOR${ref.field}",
                                timeframe = decl.timeframe,
                                warmupBars = decl.warmupBars,
                            )
                        }
                        StreamFieldRef(alias, "close")
                    }
                },
            )

        val rewritten =
            ast.copy(
                lets = ast.lets.map { it.copy(expr = transform.expr(it.expr)) },
                rules =
                    ast.rules.map { rule ->
                        when (rule) {
                            is WhenThen ->
                                WhenThen(
                                    cond = transform.expr(rule.cond),
                                    action = transform.action(rule.action),
                                    line = rule.line,
                                )
                        }
                    },
                schedules = ast.schedules.map { it.copy(action = transform.action(it.action)) },
                sequences =
                    ast.sequences.map { sequence ->
                        sequence.copy(
                            stages =
                                sequence.stages.map { stage ->
                                    stage.copy(condition = transform.expr(stage.condition))
                                },
                        )
                    },
                defaults = ast.defaults?.let { transform.defaultsBlock(it) },
            )
        val streams = rewritten.streams.filter { it.alias !in byAlias } + hidden.values
        return Expanded(rewritten.copy(streams = streams), byAlias.keys)
    }

    /**
     * The dataset aliases an earlier pass already expanded: each hidden field stream is named
     * `<dataset>/<field>`, so the dataset is the part before the separator.
     */
    private fun datasetAliasesOf(ast: StrategyAst): Set<String> =
        ast.streams
            .filter { it.broker.equals(HUB_BROKER, ignoreCase = true) && SEPARATOR in it.alias }
            .map { it.alias.substringBefore(SEPARATOR) }
            .toSet()

    fun hiddenAlias(
        alias: String,
        field: String,
    ): String = "$alias$SEPARATOR$field"
}
