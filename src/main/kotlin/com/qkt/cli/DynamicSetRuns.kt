package com.qkt.cli

import com.qkt.app.setMemberWarmup
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
 * A backtest's symbol sets (runtime membership plan, steps 4-5). Filtered sets resolve once, at `--from`
 * ([SymbolSetResolution]); each set whose members move (`DYNAMIC`, or reading market fields) instead becomes its
 * candidates: every member some close of the run's window picks, or for a set reading market fields every row it
 * could pick. The run declares and feeds those candidates' streams, and the strategy runs as a [DynamicSetHost] that
 * steps the set's membership from the same catalog rows (and the market figures it watches) at each close.
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
        private val candidates: Map<String, List<String>>,
    ) {
        /**
         * The strategy for one run with [overrides] substituted into its `PARAM`s, with fresh membership every call;
         * [journal] observes each membership change beside the run's bus. A joining member reads the history it lacks
         * from the run's market source; a [live] run does not seed every candidate at start (the joins do), and ranks
         * only the candidates its session subscribed, so it never picks a member it cannot feed.
         */
        fun strategy(
            overrides: Map<String, String>,
            live: Boolean = false,
            journal: (SetMembershipChanged) -> Unit = {},
        ): Strategy =
            DynamicSetHost(
                AstCompiler().compile(ast, overrides) as CompiledStrategy,
                sets.map { (decl, spec, rows) ->
                    HostedSet(
                        SetMemberTemplate(source, decl.alias, overrides),
                        DynamicSetMembership(
                            spec,
                            ranked(decl.alias, rows, live),
                            TimeWindow.parse(decl.timeframe).durationMs,
                        ),
                        spec.venue,
                    )
                },
                warmup = ::setMemberWarmup,
                seedCandidates = !live,
                journal = journal,
            )

        /** The rows a set ranks: all of them in a backtest, live only the candidates its session subscribed. */
        private fun ranked(
            alias: String,
            rows: List<InstrumentMeta>,
            live: Boolean,
        ): List<InstrumentMeta> {
            if (!live) return rows
            val fed = candidates.getValue(alias).toSet()
            return rows.filter { it.qktSymbol.substringAfter(':') in fed }
        }
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
        val listed = SymbolSetResolution.catalog(args, dataRoot)
        val catalog = SymbolSetResolution.FileCatalog(listed)
        val fromMs = BacktestContext.parseInstant(from).toEpochMilli()
        val accepted = args.flag(SymbolSetResolution.SURVIVORSHIP_FLAG)
        val resolved = SymbolSetResolution.resolve(ast, catalog, fromMs, accepted, keepDynamic = true)
        val toMs = args.option("to")?.let { BacktestContext.parseInstant(it).toEpochMilli() } ?: fromMs
        // Moving sets also see the event catalogs, point in time: a contract is a row from its listing to its expiry.
        val events = EventCatalogRows.of(args, dataRoot)
        val moving = SymbolSetResolution.FileCatalog((listed + events).distinctBy { it.qktSymbol })
        val plan = plan(resolved, moving, fromMs, toMs, accepted) ?: return Sets(resolved, null)
        return Sets(plan.ast, plan)
    }

    /**
     * The plan for [ast]'s `DYNAMIC` sets over `[fromMs, toMs]`, null when it has none. A set's candidates are the
     * members of every close of its window in that span, plus the symbols of its venue in [held] (a live restart's
     * positions, which stay as sticky members), in symbol order; a set no close gives a member is refused.
     */
    fun plan(
        ast: StrategyAst,
        catalog: SetCatalog,
        fromMs: Long,
        toMs: Long,
        survivorshipAccepted: Boolean,
        held: Set<String> = emptySet(),
        note: (String) -> Unit = { System.err.println("qkt: note: $it") },
    ): Plan? {
        val dynamic =
            ast.streams.mapNotNull { decl ->
                (SymbolSetSpec.of(decl) as? SymbolSetSpec.Filtered)?.takeIf { it.moves }?.let { decl to it }
            }
        if (dynamic.isEmpty()) return null
        val unions = ast.streams.mapNotNull { SymbolSetSpec.of(it) as? SymbolSetSpec.Union }
        val unioned = dynamic.firstOrNull { (decl, _) -> unions.any { decl.alias in it.operands } }
        if (unioned != null) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '${unioned.first.alias}' is DYNAMIC, so it cannot be part of a union",
            )
        }
        val marketSlots = dynamic.firstOrNull { (_, spec) -> spec.slot != null && !spec.ladder && spec.readsMarket }
        if (marketSlots != null) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '${marketSlots.first.alias}' is a slot universe, which cannot read market fields yet",
            )
        }
        val sets =
            dynamic.map { (decl, spec) ->
                Triple(decl, spec, SymbolSetResolution.rows(decl.alias, spec, catalog, survivorshipAccepted).rows)
            }
        val candidates =
            sets.associate { (decl, spec, rows) ->
                val holding =
                    held.filter { it.substringBefore(':').equals(spec.venue, true) }.map { it.substringAfter(':') }
                decl.alias to (candidates(decl, spec, rows, fromMs, toMs) + holding).distinct().sorted()
            }
        for ((alias, members) in candidates) {
            note("symbol set '$alias': DYNAMIC over ${members.size} candidate(s): ${members.joinToString()}")
        }
        val expanded = SymbolSetExpansion.expand(ast.streams, emptyList()) { alias, _ -> candidates[alias] }
        val rules = ast.rules.filterNot { it is ForEachSet && it.set in candidates }
        return Plan(ast.copy(streams = expanded.streams, rules = rules), ast, sets, candidates)
    }

    private fun candidates(
        decl: StreamDecl,
        spec: SymbolSetSpec.Filtered,
        rows: List<InstrumentMeta>,
        fromMs: Long,
        toMs: Long,
    ): List<String> {
        val picked =
            DynamicSetMembership.candidatesOver(spec, rows, TimeWindow.parse(decl.timeframe).durationMs, fromMs, toMs)
        if (picked.isEmpty()) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '${decl.alias}': no ${spec.venue} instrument passes ${spec.filter.canonical} at any " +
                    "close of the run",
            )
        }
        return picked
    }
}
