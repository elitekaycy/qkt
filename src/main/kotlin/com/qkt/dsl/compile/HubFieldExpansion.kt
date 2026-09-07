package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell
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
        if (hubDecls.isEmpty()) return Expanded(ast, emptySet())

        val byAlias = hubDecls.associateBy { it.alias }
        for (rule in ast.rules) {
            val targets =
                when (rule) {
                    is WhenThen -> orderTargets(rule.action)
                }
            val traded = targets.filter { it in byAlias }
            require(traded.isEmpty()) {
                "Series '${traded.first()}' is read-only -- a hub dataset carries a published statistic, " +
                    "not a tradeable price; it cannot be bought, sold, closed, cancelled or resized"
            }
        }
        for (schedule in ast.schedules) {
            val traded = orderTargets(schedule.action).filter { it in byAlias }
            require(traded.isEmpty()) { "Series '${traded.first()}' is read-only -- a hub dataset cannot be traded" }
        }
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
     * Every stream an action would trade, cancel or resize. Exhaustive over `ActionAst`, with no
     * `else`, so a new action variant is a compile error here rather than a silent miss.
     */
    private fun orderTargets(a: ActionAst): List<String> =
        when (a) {
            is Buy -> listOf(a.stream)
            is Sell -> listOf(a.stream)
            is Close -> listOf(a.stream)
            is Resize -> listOf(a.stream)
            CloseAll -> emptyList()
            is Cancel -> listOf(a.stream)
            CancelAll -> emptyList()
            is Log -> emptyList()
            is Block -> a.actions.flatMap(::orderTargets)
            is OcoEntry -> orderTargets(a.leg1) + orderTargets(a.leg2)
            is Latch -> listOf(a.stream)
        }

    fun hiddenAlias(
        alias: String,
        field: String,
    ): String = "$alias$SEPARATOR$field"
}
