package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CompiledStrategy
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.DynamicSetMembership
import com.qkt.dsl.sets.HostedSet
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SetMemberTemplate
import com.qkt.dsl.sets.SymbolSetExpansion
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.InstrumentMeta
import com.qkt.strategy.Strategy
import java.nio.file.Path

/**
 * A backtest's symbol sets (runtime membership plan, step 4). Filtered sets resolve once, at `--from`
 * ([SymbolSetResolution]); each `DYNAMIC` set instead becomes every member some close of the run's window picks, so
 * the run declares and feeds those candidates' streams, and the strategy runs as a [DynamicSetHost] that steps the
 * set's membership from the same catalog rows at each close of its window.
 */
internal object DynamicSetRuns {
    /** The strategy a backtest declares ([ast]) and how it builds the strategy that runs, null without DYNAMIC sets. */
    class Sets(
        val ast: StrategyAst,
        val plan: Plan?,
    )

    /** How a run with `DYNAMIC` sets builds its strategy: [ast] declares the candidates, [source] keeps the sets. */
    class Plan(
        val ast: StrategyAst,
        private val source: StrategyAst,
        private val sets: List<Triple<StreamDecl, SymbolSetSpec.Filtered, List<InstrumentMeta>>>,
    ) {
        /**
         * The strategy for one run with [overrides] substituted into its `PARAM`s, with fresh membership every call;
         * [journal] observes each membership change beside the run's bus.
         */
        fun strategy(
            overrides: Map<String, String>,
            journal: (SetMembershipChanged) -> Unit = {},
        ): Strategy =
            DynamicSetHost(
                AstCompiler().compile(ast, overrides) as CompiledStrategy,
                sets.map { (decl, spec, rows) ->
                    HostedSet(
                        SetMemberTemplate(source, decl.alias, overrides),
                        DynamicSetMembership(spec, rows, TimeWindow.parse(decl.timeframe).durationMs),
                        spec.venue,
                    )
                },
                journal = journal,
            )
    }

    /** [ast]'s sets for a backtest over [args]: filtered sets as of `--from` from the instruments file. */
    fun forBacktest(
        ast: StrategyAst,
        args: Args,
    ): Sets {
        if (ast.streams.none { it.broker == SET_BROKER }) return Sets(ast, null)
        val from =
            args.option("from")
                ?: throw BacktestContext.Companion.SetupError(
                    "a filtered symbol set is resolved as of --from, which is missing",
                )
        val dataRoot = args.option("data-root")?.let(Path::of) ?: CliDataRoot.resolve(args).path
        val catalog = SymbolSetResolution.FileCatalog(SymbolSetResolution.catalog(args, dataRoot))
        val fromMs = BacktestContext.parseInstant(from).toEpochMilli()
        val accepted = args.flag(SymbolSetResolution.SURVIVORSHIP_FLAG)
        val resolved = SymbolSetResolution.resolve(ast, catalog, fromMs, accepted, keepDynamic = true)
        val toMs = args.option("to")?.let { BacktestContext.parseInstant(it).toEpochMilli() } ?: fromMs
        val plan = plan(resolved, catalog, fromMs, toMs, accepted) ?: return Sets(resolved, null)
        return Sets(plan.ast, plan)
    }

    /**
     * The plan for [ast]'s `DYNAMIC` sets over `[fromMs, toMs]`, null when it has none. A set's candidates are the
     * members of every close of its window in that span, in symbol order; a set no close gives a member is refused.
     */
    fun plan(
        ast: StrategyAst,
        catalog: SetCatalog,
        fromMs: Long,
        toMs: Long,
        survivorshipAccepted: Boolean,
        note: (String) -> Unit = { System.err.println("qkt: note: $it") },
    ): Plan? {
        val dynamic =
            ast.streams.mapNotNull { decl ->
                (SymbolSetSpec.of(decl) as? SymbolSetSpec.Filtered)?.takeIf { it.dynamic }?.let { decl to it }
            }
        if (dynamic.isEmpty()) return null
        val unions = ast.streams.mapNotNull { SymbolSetSpec.of(it) as? SymbolSetSpec.Union }
        val unioned = dynamic.firstOrNull { (decl, _) -> unions.any { decl.alias in it.operands } }
        if (unioned != null) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '${unioned.first.alias}' is DYNAMIC, so it cannot be part of a union",
            )
        }
        val sets =
            dynamic.map { (decl, spec) ->
                Triple(decl, spec, SymbolSetResolution.rows(decl.alias, spec, catalog, survivorshipAccepted).rows)
            }
        val candidates =
            sets.associate { (decl, spec, rows) -> decl.alias to candidates(decl, spec, rows, fromMs, toMs) }
        for ((alias, members) in candidates) {
            note("symbol set '$alias': DYNAMIC over ${members.size} candidate(s): ${members.joinToString()}")
        }
        val expanded = SymbolSetExpansion.expand(ast.streams, emptyList()) { alias, _ -> candidates[alias] }
        val rules = ast.rules.filterNot { it is ForEachSet && it.set in candidates }
        return Plan(ast.copy(streams = expanded.streams, rules = rules), ast, sets)
    }

    private fun candidates(
        decl: StreamDecl,
        spec: SymbolSetSpec.Filtered,
        rows: List<InstrumentMeta>,
        fromMs: Long,
        toMs: Long,
    ): List<String> {
        val windowMs = TimeWindow.parse(decl.timeframe).durationMs
        val membership = DynamicSetMembership(spec, rows, windowMs)
        val picked = sortedSetOf<String>()
        var closeMs = fromMs - Math.floorMod(fromMs, windowMs)
        while (closeMs <= toMs) {
            picked += membership.membersAt(closeMs)
            closeMs += windowMs
        }
        if (picked.isEmpty()) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '${decl.alias}': no ${spec.venue} instrument passes ${spec.filter.canonical} at any " +
                    "close of the run",
            )
        }
        return picked.toList()
    }
}
