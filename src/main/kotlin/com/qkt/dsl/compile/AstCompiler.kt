package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream

/**
 * Compiles a parsed [StrategyAst] into a runnable [Strategy] (a [DslCompiledStrategy]). It
 * expands hub fields and params, builds the stream table, runs the compile-time checks
 * (read-only orders, baskets, brackets, RESIZE protection, chained comparisons), then compiles
 * snapshots, rules, sequences and schedules in a fixed order that fixes indicator binding order.
 */
class AstCompiler {
    fun compile(
        rawAst: StrategyAst,
        overrides: Map<String, String> = emptyMap(),
    ): Strategy {
        // Hub datasets are expanded into one stream per referenced field before anything else
        // sees the AST, so every later stage handles a hub field exactly like a candle close.
        val expanded = HubFieldExpansion.apply(ParamSubstitution.apply(rawAst, overrides))
        val ast = expanded.ast
        // Real streams keep their venue identity; each basket is a synthetic stream with a
        // `BASKET:` identity whose composite candle is written into the hub at sync time.
        val streams: Map<String, HubKey> =
            ast.streams.associate { it.alias to HubKey(it.broker, it.symbol, it.timeframe) } +
                ast.baskets.associate { it.alias to HubKey("BASKET", it.alias.uppercase(), it.timeframe) } +
                ast.series.associate { it.alias to HubKey(it.source.broker, it.source.symbol, it.timeframe) }
        // alias -> constituent aliases, for fanning basket orders out and reading basket positions.
        val basketConstituents: Map<String, List<String>> = ast.baskets.associate { it.alias to it.constituents }
        val resolver = LetResolver(ast.lets, streams.keys)
        val bindings = IndicatorBinding.Bag()
        val aggregates = AggregateBinding.Bag()
        val exprCompiler = ExprCompiler(bindings, aggregates, basketConstituents)
        val exitExprCompiler =
            ExprCompiler(
                bindings = bindings,
                aggregates = aggregates,
                baskets = basketConstituents,
                allowExitAccess = true,
            )
        val strategyLogger = org.slf4j.LoggerFactory.getLogger("com.qkt.dsl.strategy.${ast.name}")
        val ids = com.qkt.common.SequentialIdGenerator(prefix = "dsl-${ast.name}-")
        val pendingStacks = PendingStacks()
        val exitHookCatalog =
            ExitHookCatalog(fingerprintContext = exitHookFingerprintContext(streams, basketConstituents))
        val actionCompiler =
            ActionCompiler(
                exprCompiler,
                strategyLogger,
                ids,
                pendingStacks,
                basketConstituents,
                exitExprCompiler,
                exitHookCatalog,
            )

        val whenThens: List<WhenThen> =
            ast.rules.map {
                require(it is WhenThen) { "Only WHEN-THEN rules are supported" }
                it
            }
        val readOnlyAliases = readOnlyAliases(streams, expanded.datasetAliases)
        whenThens.forEach { rejectReadOnlyOrders(it.action, readOnlyAliases) }
        validateBaskets(ast)
        validateCompleteBrackets(ast)
        validateResizeProtection(ast)
        val resolvedConditions: List<ExprAst> = whenThens.map { resolver.resolve(it.cond) }
        resolvedConditions.forEach(::rejectChainedComparisons)
        val resolvedSequenceConditions: List<ExprAst> =
            ast.sequences.flatMap { sequence -> sequence.stages.map { resolver.resolve(it.condition) } }
        val plan = SnapshotPlan.scan(resolvedConditions + resolvedSequenceConditions)

        val maxRollingPerName: Map<String, Int> = plan.rollingMaxN
        val snapshotStore = SnapshotStore(maxRollingPerName)

        val letRhsByName: Map<String, ExprAst> = ast.lets.associate { it.name to resolver.resolve(it.expr) }

        val capturableNames: Set<String> =
            (plan.captureOnBuy + plan.captureOnSell + plan.captureOnOpen + plan.rollingMaxN.keys).toSet()
        val letCompiledRhs: Map<String, CompiledExpr> =
            capturableNames.associateWith { name ->
                val rhs = letRhsByName[name] ?: error("Snapshot/rolling LET '$name' not declared")
                exprCompiler.compile(rhs)
            }

        val rules: List<CompiledRule> =
            WhenThenCompiler.compileAll(
                whenThens,
                resolvedConditions,
                streams,
                ast.defaults,
                resolver,
                exprCompiler,
                actionCompiler,
                plan,
                letCompiledRhs,
            )
        val maxRolling = plan.rollingMaxN.values.maxOrNull() ?: 0
        val retention = maxOf(1, maxRolling + 1)
        val retentionByKey: Map<HubKey, Int> =
            streams.values.associateWith { retention }

        val stackAtSymbols: Set<String> =
            whenThens
                .flatMap { collectStackAtSymbols(it.action, streams) }
                .toSet()
        val sequenceRuntime = SequenceCompiler.compile(ast.sequences, streams, exprCompiler, resolver)
        sequenceRuntime.bindRuleEdges(rules)

        // Symbols whose feed must supply volume because a VWAP/OBV binds to them (#301).
        val volumeRequiringSymbols: Set<String> = volumeRequiringSymbols(bindings, streams)

        val metaRefs = collectMetaRefs(ast, streams)
        val quoteFieldStreams = collectQuoteFieldStreams(resolvedConditions + resolvedSequenceConditions)

        val perStreamWarmup: Map<String, Int> = WarmupRequirements.compute(ast)
        val warmupGate = WarmupGate(perStreamWarmup)

        val perStreamWarmupSpec: Map<WarmupStream, WarmupSpec> = perStreamWarmupSpecs(perStreamWarmup, streams)

        val compiledSchedules: List<CompiledSchedule> =
            ast.schedules.map { decl ->
                CompiledSchedule(
                    decl = decl,
                    action = actionCompiler.compile(resolver.resolve(mergeDefaults(decl.action, ast.defaults))),
                )
            }

        return CompiledStrategy(
            strategyFingerprint = sha256(ast.toString()),
            ids = ids,
            streams = streams,
            retentionByKey = retentionByKey,
            bindings = bindings,
            aggregates = aggregates,
            snapshotStore = snapshotStore,
            plan = plan,
            letCompiledRhs = letCompiledRhs,
            transitions = PositionTransitions(),
            rules = rules,
            pendingStacks = pendingStacks,
            multiPositionPerSymbolSymbols = stackAtSymbols,
            volumeRequiringSymbols = volumeRequiringSymbols,
            usesBookSizing = actionCompiler.usesBookSizing,
            metaRefs = metaRefs,
            warmupGate = warmupGate,
            perStreamWarmup = perStreamWarmupSpec,
            syncGroups = ast.syncGroups,
            schedules = compiledSchedules,
            quoteFieldStreams = quoteFieldStreams,
            baskets = ast.baskets,
            sequenceRuntime = sequenceRuntime,
            exitHookCatalog = exitHookCatalog,
        )
    }
}
