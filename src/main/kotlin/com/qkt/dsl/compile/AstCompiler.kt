package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SequenceDecl
import com.qkt.dsl.ast.SeriesSymbols
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.SyncGroupDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import java.math.BigDecimal

class AstCompiler {
    fun compile(
        rawAst: StrategyAst,
        overrides: Map<String, String> = emptyMap(),
    ): Strategy {
        val ast = ParamSubstitution.apply(rawAst, overrides)
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
            ExitHookCatalog(
                fingerprintContext =
                    buildString {
                        streams.toSortedMap().forEach { (alias, key) ->
                            append(alias)
                            append('=')
                            append(key)
                            append('\n')
                        }
                        basketConstituents.toSortedMap().forEach { (alias, members) ->
                            append("basket:")
                            append(alias)
                            append('=')
                            append(members.joinToString(","))
                            append('\n')
                        }
                    },
            )
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
        // Macro series (MACRO:) are read-only — they carry a published statistic, not a tradeable
        // price. Reject any order action targeting one at compile time (#440).
        val readOnlyAliases = streams.filterValues { it.broker == "MACRO" || it.broker == SeriesSymbols.BROKER }.keys
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
            whenThens.zip(resolvedConditions).mapIndexed { ruleIndex, (rule, cond) ->
                val primary: ActionAst =
                    when (val a = rule.action) {
                        is Block -> a.actions.firstOrNull { it !is Log } ?: a.actions.first()
                        is OcoEntry -> a.leg1
                        else -> a
                    }
                val streamAlias: String? =
                    when (primary) {
                        is Buy -> primary.stream
                        is Sell -> primary.stream
                        is Close -> primary.stream
                        is Cancel -> primary.stream
                        is CloseAll, is CancelAll, is Log -> null
                        else -> null
                    }
                val ruleAlias =
                    streamAlias
                        ?: streams.keys.firstOrNull()
                        ?: error("Strategy must declare at least one stream")
                val ruleSymbol =
                    streams[ruleAlias]?.qktSymbol
                        ?: error("Unknown stream alias: $ruleAlias")
                val compiledCond = exprCompiler.compile(cond, ruleAlias = ruleAlias)
                val mergedAction = resolver.resolve(mergeDefaults(rule.action, ast.defaults))
                val action = actionCompiler.compile(mergedAction)
                val isBuy = primary is Buy
                val isSell = primary is Sell
                val referencedAliases = collectStreamAliases(rule)
                CompiledRule(
                    condition = compiledCond,
                    action = action,
                    ruleAlias = ruleAlias,
                    ruleSymbol = ruleSymbol,
                    isBuy = isBuy,
                    isSell = isSell,
                    onBuyCaptures = plan.captureOnBuy.map { it to letCompiledRhs.getValue(it) },
                    onSellCaptures = plan.captureOnSell.map { it to letCompiledRhs.getValue(it) },
                    onOpenCaptures = plan.captureOnOpen.map { it to letCompiledRhs.getValue(it) },
                    referencedAliases = referencedAliases,
                    consumesSequenceCompletion = readsSequenceCompletion(cond),
                    edgeStateKey = "$ruleAlias#$ruleIndex",
                )
            }
        val maxRolling = plan.rollingMaxN.values.maxOrNull() ?: 0
        val retention = maxOf(1, maxRolling + 1)
        val retentionByKey: Map<HubKey, Int> =
            streams.values.associateWith { retention }

        val stackAtSymbols: Set<String> =
            whenThens
                .flatMap { collectStackAtSymbols(it.action, streams) }
                .toSet()
        val sequenceRuntime = compileSequences(ast.sequences, streams, exprCompiler, resolver)
        sequenceRuntime.bindRuleEdges(rules)

        // Symbols whose feed must supply volume because a VWAP/OBV binds to them (#301).
        val volumeRequiringSymbols: Set<String> =
            bindings.volumeRequiringAliases
                .mapNotNull { alias ->
                    val key = streams[alias] ?: return@mapNotNull null
                    if (key.broker == SeriesSymbols.BROKER) null else key.qktSymbol
                }.toSet()

        val metaRefs = collectMetaRefs(ast, streams)
        val quoteFieldStreams = collectQuoteFieldStreams(resolvedConditions + resolvedSequenceConditions)

        val perStreamWarmup: Map<String, Int> = WarmupRequirements.compute(ast)
        val warmupGate = WarmupGate(perStreamWarmup)

        val perStreamWarmupSpec: Map<WarmupStream, WarmupSpec> =
            perStreamWarmup
                .mapNotNull { (alias, bars) ->
                    val key = streams[alias] ?: return@mapNotNull null
                    if (key.broker == SeriesSymbols.BROKER) return@mapNotNull null
                    val window =
                        com.qkt.candles.TimeWindow
                            .parse(key.timeframe)
                    WarmupStream(key.qktSymbol, window) to bars
                }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (stream, counts) ->
                    WarmupSpec.Bars(stream.window, counts.max())
                }

        val compiledSchedules: List<CompiledSchedule> =
            ast.schedules.map { decl ->
                CompiledSchedule(
                    decl = decl,
                    action = actionCompiler.compile(resolver.resolve(mergeDefaults(decl.action, ast.defaults))),
                )
            }

        return CompiledStrategy(
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

    private fun compileSequences(
        sequences: List<SequenceDecl>,
        streams: Map<String, HubKey>,
        exprCompiler: ExprCompiler,
        resolver: LetResolver,
    ): SequenceRuntime {
        require(sequences.map { it.name }.toSet().size == sequences.size) {
            "Duplicate SEQUENCE name in: ${sequences.map { it.name }}"
        }
        return SequenceRuntime(
            sequences.map { sequence ->
                val key = streams[sequence.stream] ?: error("Unknown SEQUENCE stream alias: ${sequence.stream}")
                require(
                    sequence.stages
                        .map { it.name }
                        .toSet()
                        .size == sequence.stages.size,
                ) {
                    "Duplicate STAGE name in SEQUENCE '${sequence.name}': ${sequence.stages.map { it.name }}"
                }
                val resolvedStages = sequence.stages.map { it.name to resolver.resolve(it.condition) }
                CompiledSequence(
                    name = sequence.name,
                    streamAlias = sequence.stream,
                    streamSymbol = key.qktSymbol,
                    stages =
                        sequence.stages.zip(resolvedStages).map { (stage, resolved) ->
                            CompiledSequenceStage(
                                name = stage.name,
                                withinMs = stage.within?.millis,
                                condition = exprCompiler.compile(resolved.second, ruleAlias = sequence.stream),
                            )
                        },
                    referencedAliases =
                        (
                            resolvedStages.flatMap { collectExprStreamAliases(it.second) } + sequence.stream
                        ).toSet(),
                )
            },
        )
    }

    private fun collectExprStreamAliases(expr: ExprAst): Set<String> {
        val out = mutableSetOf<String>()

        fun walk(e: ExprAst) {
            when (e) {
                is com.qkt.dsl.ast.StreamFieldRef -> out.add(e.stream)
                is com.qkt.dsl.ast.PositionRef -> out.add(e.stream)
                is com.qkt.dsl.ast.BinaryOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.UnaryOp -> walk(e.arg)
                is com.qkt.dsl.ast.CmpOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.Crosses -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.FuncCall -> e.args.forEach(::walk)
                is com.qkt.dsl.ast.IndicatorCall -> e.args.forEach(::walk)
                is com.qkt.dsl.ast.Aggregate -> walk(e.series)
                is com.qkt.dsl.ast.Between -> {
                    walk(e.v)
                    walk(e.lo)
                    walk(e.hi)
                }
                is com.qkt.dsl.ast.InList -> {
                    walk(e.v)
                    e.members.forEach(::walk)
                }
                is com.qkt.dsl.ast.CaseWhen -> {
                    e.branches.forEach { (c, b) ->
                        walk(c)
                        walk(b)
                    }
                    walk(e.elseExpr)
                }
                is com.qkt.dsl.ast.IsNull -> walk(e.expr)
                is com.qkt.dsl.ast.NumLit,
                is com.qkt.dsl.ast.BoolLit,
                is com.qkt.dsl.ast.StringLit,
                is com.qkt.dsl.ast.Ref,
                is com.qkt.dsl.ast.NowAccessor,
                is com.qkt.dsl.ast.CalendarWindow,
                is com.qkt.dsl.ast.SessionWindow,
                com.qkt.dsl.ast.LastTradingDayOfMonth,
                is com.qkt.dsl.ast.AccountRef,
                is com.qkt.dsl.ast.StreakRef,
                is com.qkt.dsl.ast.TradesRef,
                is com.qkt.dsl.ast.CooldownRef,
                is com.qkt.dsl.ast.StateAccessor,
                com.qkt.dsl.ast.StackEntryRef,
                com.qkt.dsl.ast.EntryQty,
                is com.qkt.dsl.ast.ExitRef,
                is com.qkt.dsl.ast.SequenceAccessor,
                -> Unit
            }
        }
        walk(expr)
        return out
    }

    /** Aliases whose conditions read `bid`/`ask`/`spread` — see [DslCompiledStrategy.quoteFieldStreams]. */
    private fun collectQuoteFieldStreams(conditions: List<ExprAst>): Set<String> {
        val out = mutableSetOf<String>()

        fun walk(e: ExprAst) {
            when (e) {
                is com.qkt.dsl.ast.StreamFieldRef ->
                    if (e.field in setOf("bid", "ask", "spread")) out.add(e.stream)
                is com.qkt.dsl.ast.BinaryOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.UnaryOp -> walk(e.arg)
                is com.qkt.dsl.ast.CmpOp -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.Crosses -> {
                    walk(e.lhs)
                    walk(e.rhs)
                }
                is com.qkt.dsl.ast.FuncCall -> e.args.forEach(::walk)
                is com.qkt.dsl.ast.IndicatorCall -> e.args.forEach(::walk)
                is com.qkt.dsl.ast.Aggregate -> walk(e.series)
                is com.qkt.dsl.ast.Between -> {
                    walk(e.v)
                    walk(e.lo)
                    walk(e.hi)
                }
                is com.qkt.dsl.ast.InList -> {
                    walk(e.v)
                    e.members.forEach(::walk)
                }
                is com.qkt.dsl.ast.CaseWhen -> {
                    e.branches.forEach { (c, b) ->
                        walk(c)
                        walk(b)
                    }
                    walk(e.elseExpr)
                }
                is com.qkt.dsl.ast.IsNull -> walk(e.expr)
                is com.qkt.dsl.ast.NumLit,
                is com.qkt.dsl.ast.BoolLit,
                is com.qkt.dsl.ast.StringLit,
                is com.qkt.dsl.ast.Ref,
                is com.qkt.dsl.ast.NowAccessor,
                is com.qkt.dsl.ast.CalendarWindow,
                is com.qkt.dsl.ast.SessionWindow,
                com.qkt.dsl.ast.LastTradingDayOfMonth,
                is com.qkt.dsl.ast.AccountRef,
                is com.qkt.dsl.ast.StreakRef,
                is com.qkt.dsl.ast.TradesRef,
                is com.qkt.dsl.ast.CooldownRef,
                is com.qkt.dsl.ast.PositionRef,
                is com.qkt.dsl.ast.StateAccessor,
                com.qkt.dsl.ast.StackEntryRef,
                com.qkt.dsl.ast.EntryQty,
                is com.qkt.dsl.ast.ExitRef,
                is com.qkt.dsl.ast.SequenceAccessor,
                -> Unit
            }
        }
        conditions.forEach(::walk)
        return out
    }

    private fun collectStackAtSymbols(
        action: ActionAst,
        streams: Map<String, HubKey>,
    ): Set<String> {
        val out = mutableSetOf<String>()

        fun walk(a: ActionAst) {
            when (a) {
                is Buy ->
                    if (a.opts.stackAts.isNotEmpty()) {
                        streams[a.stream]?.qktSymbol?.let { out.add(it) }
                    }
                is Sell ->
                    if (a.opts.stackAts.isNotEmpty()) {
                        streams[a.stream]?.qktSymbol?.let { out.add(it) }
                    }
                is Block -> a.actions.forEach { walk(it) }
                is OcoEntry -> {
                    walk(a.leg1)
                    walk(a.leg2)
                }
                else -> {} // other actions don't carry stackAts
            }
        }
        walk(action)
        return out
    }

    private fun rejectReadOnlyOrders(
        action: ActionAst,
        readOnlyAliases: Set<String>,
    ) {
        fun reject(stream: String) =
            require(stream !in readOnlyAliases) {
                "Series '$stream' is read-only — it has no tradeable price; remove the order " +
                    "action targeting it (BUY/SELL/CLOSE/CANCEL)."
            }
        when (action) {
            is Buy -> {
                reject(action.stream)
                rejectNestedOrders(action.opts, readOnlyAliases)
            }
            is Sell -> {
                reject(action.stream)
                rejectNestedOrders(action.opts, readOnlyAliases)
            }
            is Close -> reject(action.stream)
            is Resize -> reject(action.stream)
            is Cancel -> reject(action.stream)
            is Latch -> {
                reject(action.stream)
                action.entries.mapNotNull { it.stream }.forEach(::reject)
            }
            is OcoEntry -> {
                rejectReadOnlyOrders(action.leg1, readOnlyAliases)
                rejectReadOnlyOrders(action.leg2, readOnlyAliases)
            }
            is Block -> action.actions.forEach { rejectReadOnlyOrders(it, readOnlyAliases) }
            CloseAll, CancelAll, is Log -> Unit
        }
    }

    private fun rejectChainedComparisons(expr: ExprAst) {
        fun walk(current: ExprAst) {
            when (current) {
                is com.qkt.dsl.ast.CmpOp -> {
                    require(current.lhs !is com.qkt.dsl.ast.CmpOp && current.rhs !is com.qkt.dsl.ast.CmpOp) {
                        "Chained comparisons are not supported; combine explicit comparisons with AND"
                    }
                    walk(current.lhs)
                    walk(current.rhs)
                }
                is com.qkt.dsl.ast.BinaryOp -> {
                    walk(current.lhs)
                    walk(current.rhs)
                }
                is com.qkt.dsl.ast.UnaryOp -> walk(current.arg)
                is com.qkt.dsl.ast.Crosses -> {
                    walk(current.lhs)
                    walk(current.rhs)
                }
                is com.qkt.dsl.ast.FuncCall -> current.args.forEach(::walk)
                is com.qkt.dsl.ast.IndicatorCall -> current.args.forEach(::walk)
                is com.qkt.dsl.ast.Aggregate -> walk(current.series)
                is com.qkt.dsl.ast.Between -> {
                    walk(current.v)
                    walk(current.lo)
                    walk(current.hi)
                }
                is com.qkt.dsl.ast.InList -> {
                    walk(current.v)
                    current.members.forEach(::walk)
                }
                is com.qkt.dsl.ast.CaseWhen -> {
                    current.branches.forEach { (condition, branch) ->
                        walk(condition)
                        walk(branch)
                    }
                    walk(current.elseExpr)
                }
                is com.qkt.dsl.ast.IsNull -> walk(current.expr)
                is com.qkt.dsl.ast.NumLit,
                is com.qkt.dsl.ast.BoolLit,
                is com.qkt.dsl.ast.StringLit,
                is com.qkt.dsl.ast.Ref,
                is com.qkt.dsl.ast.StreamFieldRef,
                is com.qkt.dsl.ast.NowAccessor,
                is com.qkt.dsl.ast.CalendarWindow,
                is com.qkt.dsl.ast.SessionWindow,
                com.qkt.dsl.ast.LastTradingDayOfMonth,
                is com.qkt.dsl.ast.AccountRef,
                is com.qkt.dsl.ast.StreakRef,
                is com.qkt.dsl.ast.TradesRef,
                is com.qkt.dsl.ast.CooldownRef,
                is com.qkt.dsl.ast.PositionRef,
                is com.qkt.dsl.ast.StateAccessor,
                com.qkt.dsl.ast.StackEntryRef,
                com.qkt.dsl.ast.EntryQty,
                is com.qkt.dsl.ast.ExitRef,
                is com.qkt.dsl.ast.SequenceAccessor,
                -> Unit
            }
        }
        walk(expr)
    }

    private fun rejectNestedOrders(
        opts: ActionOpts,
        readOnlyAliases: Set<String>,
    ) {
        opts.onFill.forEach { rejectReadOnlyOrders(it, readOnlyAliases) }
        (opts.exitHooks.onStop + opts.exitHooks.onTakeProfit + opts.exitHooks.onClose)
            .forEach { rejectReadOnlyOrders(it, readOnlyAliases) }
    }

    private fun validateResizeProtection(ast: StrategyAst) {
        val resizedStreams = mutableSetOf<String>()
        val bracketedStreams = mutableSetOf<String>()

        fun walk(action: ActionAst) {
            when (action) {
                is Resize -> resizedStreams.add(action.stream)
                is Buy -> {
                    if (action.opts.bracket != null) bracketedStreams.add(action.stream)
                    action.opts.onFill.forEach(::walk)
                    action.opts.exitHooks.onStop
                        .forEach(::walk)
                    action.opts.exitHooks.onTakeProfit
                        .forEach(::walk)
                    action.opts.exitHooks.onClose
                        .forEach(::walk)
                }
                is Sell -> {
                    if (action.opts.bracket != null) bracketedStreams.add(action.stream)
                    action.opts.onFill.forEach(::walk)
                    action.opts.exitHooks.onStop
                        .forEach(::walk)
                    action.opts.exitHooks.onTakeProfit
                        .forEach(::walk)
                    action.opts.exitHooks.onClose
                        .forEach(::walk)
                }
                is Latch ->
                    action.entries
                        .filter { it.bracket != null }
                        .forEach { bracketedStreams.add(it.stream ?: action.stream) }
                is Block -> action.actions.forEach(::walk)
                is OcoEntry -> {
                    walk(action.leg1)
                    walk(action.leg2)
                }
                else -> Unit
            }
        }

        ast.rules
            .filterIsInstance<WhenThen>()
            .map { mergeDefaults(it.action, ast.defaults) }
            .forEach(::walk)
        ast.schedules
            .map { mergeDefaults(it.action, ast.defaults) }
            .forEach(::walk)

        val unsafe = resizedStreams.intersect(bracketedStreams)
        require(unsafe.isEmpty()) {
            "RESIZE cannot target bracket-managed positions; protective child quantities " +
                "would not track a resized parent. Remove RESIZE or BRACKET for: ${unsafe.sorted().joinToString()}"
        }
    }

    private fun validateCompleteBrackets(ast: StrategyAst) {
        fun validate(bracket: com.qkt.dsl.ast.BracketAst?) {
            if (bracket == null || (bracket.stopLoss != null && bracket.takeProfit != null)) return
            val missing =
                if (bracket.stopLoss == null) {
                    "STOP LOSS"
                } else {
                    "TAKE PROFIT"
                }
            error(
                "BRACKET requires both STOP LOSS and TAKE PROFIT; missing $missing after DEFAULTS merge",
            )
        }

        fun validateLatch(bracket: com.qkt.dsl.ast.LatchBracket?) {
            if (bracket == null || (bracket.stopLoss != null && bracket.takeProfit != null)) return
            val missing = if (bracket.stopLoss == null) "STOP LOSS" else "TAKE PROFIT"
            error("BRACKET requires both STOP LOSS and TAKE PROFIT; missing $missing")
        }

        fun walk(action: ActionAst) {
            when (action) {
                is Buy -> {
                    validate(action.opts.bracket)
                    action.opts.onFill.forEach(::walk)
                    action.opts.exitHooks.onStop
                        .forEach(::walk)
                    action.opts.exitHooks.onTakeProfit
                        .forEach(::walk)
                    action.opts.exitHooks.onClose
                        .forEach(::walk)
                }
                is Sell -> {
                    validate(action.opts.bracket)
                    action.opts.onFill.forEach(::walk)
                    action.opts.exitHooks.onStop
                        .forEach(::walk)
                    action.opts.exitHooks.onTakeProfit
                        .forEach(::walk)
                    action.opts.exitHooks.onClose
                        .forEach(::walk)
                }
                is Latch -> action.entries.forEach { validateLatch(it.bracket) }
                is Block -> action.actions.forEach(::walk)
                is OcoEntry -> {
                    walk(action.leg1)
                    walk(action.leg2)
                }
                else -> Unit
            }
        }

        ast.rules
            .filterIsInstance<WhenThen>()
            .map { mergeDefaults(it.action, ast.defaults) }
            .forEach(::walk)
        ast.schedules
            .map { mergeDefaults(it.action, ast.defaults) }
            .forEach(::walk)
    }

    /**
     * Compile-time checks for every `BASKET` declaration: each constituent must be a
     * declared real stream (not unbound and not itself a basket), and the basket's
     * timeframe must match each constituent's timeframe (so their bars share a window).
     *
     * e.g. `antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h` requires `aud` and
     * `nzd` to be declared `EVERY 1h` streams.
     */
    private fun validateBaskets(ast: StrategyAst) {
        if (ast.baskets.isEmpty()) return
        val streamTimeframes = ast.streams.associate { it.alias to it.timeframe }
        val basketAliases = ast.baskets.map { it.alias }.toSet()
        for (basket in ast.baskets) {
            for (constituent in basket.constituents) {
                require(constituent !in basketAliases) {
                    "BASKET '${basket.alias}' constituent '$constituent' is itself a basket; " +
                        "baskets of baskets are not supported."
                }
                val constituentTf = streamTimeframes[constituent]
                require(constituentTf != null) {
                    "BASKET '${basket.alias}' constituent '$constituent' is not a declared " +
                        "stream in SYMBOLS."
                }
                require(constituentTf == basket.timeframe) {
                    "BASKET '${basket.alias}' timeframe '${basket.timeframe}' does not match " +
                        "constituent '$constituent' timeframe '$constituentTf'."
                }
            }
        }
    }
}

private class CompiledStrategy(
    private val streams: Map<String, HubKey>,
    override val retentionByKey: Map<HubKey, Int>,
    private val bindings: IndicatorBinding.Bag,
    private val aggregates: AggregateBinding.Bag,
    private val snapshotStore: SnapshotStore,
    private val plan: SnapshotPlan,
    private val letCompiledRhs: Map<String, CompiledExpr>,
    private val transitions: PositionTransitions,
    private val rules: List<CompiledRule>,
    override val pendingStacks: PendingStacks,
    override val multiPositionPerSymbolSymbols: Set<String>,
    override val volumeRequiringSymbols: Set<String>,
    override val usesBookSizing: Boolean,
    private val metaRefs: List<MetaRef>,
    private val warmupGate: WarmupGate,
    override val perStreamWarmup: Map<WarmupStream, WarmupSpec>,
    private val syncGroups: List<SyncGroupDecl>,
    private val schedules: List<CompiledSchedule>,
    override val quoteFieldStreams: Set<String>,
    private val baskets: List<com.qkt.dsl.ast.BasketDecl>,
    private val sequenceRuntime: SequenceRuntime,
    private val exitHookCatalog: ExitHookCatalog,
) : DslCompiledStrategy,
    com.qkt.strategy.PerStreamWarmable {
    private val subscribedSymbols: Set<String> = streams.values.map { it.qktSymbol }.toSet()
    private var hubBound: Boolean = false
    private var boundHub: CandleHub? = null
    private var boundContext: StrategyContext? = null
    private val ruleByOrderId: MutableMap<String, CompiledRule> = mutableMapOf()
    private val ruleBySignal: java.util.IdentityHashMap<Signal, CompiledRule> = java.util.IdentityHashMap()

    override val declaredStreams: Map<String, HubKey> get() = streams

    override fun exitHookReferences(): Map<String, ExitHookRef> = exitHookCatalog.references()

    override fun executeExitHook(
        ref: ExitHookRef,
        exit: ExitContext,
        timestampMs: Long,
    ): List<Signal> {
        val definition =
            exitHookCatalog.definition(ref)
                ?: error(
                    "Exit-hook definition '${ref.definitionId}' is missing or its fingerprint changed; " +
                        "refusing to run a stale persisted hook",
                )
        val hub = boundHub ?: error("CompiledStrategy must be bound before an exit hook can execute")
        val ctx = boundContext ?: error("CompiledStrategy context is unavailable for exit-hook execution")
        val candle =
            latestKnownCandle(hub)
                ?: Candle(
                    symbol = streams.values.firstOrNull()?.qktSymbol ?: error("Strategy declares no streams"),
                    open = exit.price,
                    high = exit.price,
                    low = exit.price,
                    close = exit.price,
                    volume = BigDecimal.ZERO,
                    startTime = timestampMs,
                    endTime = timestampMs,
                )
        val eval =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = null,
                evaluationTimeMs = timestampMs,
                sequences = sequenceRuntime,
                exitContext = exit,
            )
        return definition.execute(exit.reason, eval)
    }

    override fun bindStatePersistor(
        strategyId: String,
        persistor: com.qkt.persistence.StatePersistor,
    ) {
        sequenceRuntime.bindPersistor(strategyId, persistor)
    }

    override fun onOrderRejected(clientOrderId: String) {
        ruleByOrderId.remove(clientOrderId)?.rearmAfterRejection()
        sequenceRuntime.persistRuleEdges()
    }

    override fun onOrderSubmitted(
        signal: Signal,
        clientOrderId: String,
    ) {
        val rule = ruleBySignal.remove(signal) ?: return
        ruleByOrderId[clientOrderId] = rule
        if (signal is Signal.Submit) {
            correlationIds(signal.request).forEach { ruleByOrderId[it] = rule }
        }
    }

    override fun onOrderTerminal(clientOrderId: String) {
        ruleByOrderId.remove(clientOrderId)
    }

    override fun bindSchedules(
        runner: ScheduleRunner,
        ctx: StrategyContext,
        nowMs: Long,
        emit: (Signal) -> Unit,
    ) {
        for (sched in schedules) {
            runner.register(
                strategyId = ctx.strategyId,
                schedule = sched.decl,
                emitAt = { fireAt -> fireSchedule(sched, ctx, emit, fireAt) },
                nowMs = nowMs,
            )
        }
    }

    private fun fireSchedule(
        sched: CompiledSchedule,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
        fireAt: Long,
    ): Boolean {
        val hub = boundHub ?: return false
        val syntheticCandle =
            latestKnownCandle(hub) ?: run {
                scheduleLog.warn(
                    "schedule fire skipped for strategy={} — no stream has a closed bar yet " +
                        "(warmup not complete). Trigger will retry on the next fire time.",
                    ctx.strategyId,
                )
                return false
            }
        val ec =
            EvalContext(
                candle = syntheticCandle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = null,
                evaluationTimeMs = fireAt,
                sequences = sequenceRuntime,
            )
        for (sig in sched.action(ec)) emit(sig)
        return true
    }

    private fun latestKnownCandle(hub: CandleHub): Candle? {
        for ((_, key) in streams) {
            val c = hub.latest(key)
            if (c != null) return c
        }
        return null
    }

    private companion object {
        private val scheduleLog =
            org.slf4j.LoggerFactory.getLogger("com.qkt.dsl.compile.ScheduleFire")
    }

    override fun bindToHub(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        check(!hubBound) { "CompiledStrategy already bound to a hub" }
        validateMetaRefs(ctx)
        hubBound = true
        boundHub = hub
        boundContext = ctx

        // Aliases that belong to ANY sync group are evaluated via the sync callback
        // instead of the per-stream close. Without this split, both gold and silver
        // would individually fire their rules with cross-stream data from the wrong
        // window. (#45 Phase 35.)
        val syncedAliases: Set<String> = syncGroups.flatMap { it.aliases }.toSet()
        // A basket is a synthetic stream: its candle is computed by its constituent
        // sync-group, not delivered by a per-stream feed. Skip the per-stream close path
        // for basket aliases — they evaluate from the composite write (see basket groups).
        val basketAliases: Set<String> = baskets.map { it.alias }.toSet()

        for ((alias, key) in streams) {
            if (alias in basketAliases) continue
            // Seeding the ring alone is insufficient: indicators, aggregates, rolling
            // snapshots and CROSSES state must see the same historical closes that a
            // continuous backtest saw. Replay without firing rules or position-open
            // transitions, then attach the live listener.
            for (seeded in hub.seededHistory(key)) {
                updatePerAlias(alias, seeded, hub, ctx, warmupReplay = true)
            }
            if (alias in syncedAliases) continue
            hub.onClosed(key, ctx.strategyId) { closed ->
                evaluate(alias, closed, hub, ctx, emit)
            }
        }

        for (group in syncGroups) {
            val members = group.aliases.associateWith { streams.getValue(it) }
            val groupKey = SyncGroupKey(members = members, timeoutMs = group.timeoutMs)
            hub.registerSyncGroup(groupKey, ctx.strategyId)
            hub.onSyncClosed(groupKey, ctx.strategyId) { bars ->
                // Two-pass: every alias's indicators/snapshots/aggregates update FIRST,
                // then rules fire. This ensures a gold-anchored rule that reads
                // `sma(silver.close, N)` sees silver's same-window value, not the
                // previous window's. Without this split, the alias evaluated first
                // would fire rules against the other alias's stale indicator state.
                // A basket alias in this group is only a timing gate — its own
                // update-then-fire already ran in its implicit constituent group, so it is
                // skipped here to avoid evaluating the basket twice per window.
                for (alias in group.aliases) {
                    if (alias in basketAliases) continue
                    updatePerAlias(alias, bars.getValue(alias), hub, ctx)
                }
                for (alias in group.aliases) {
                    if (alias in basketAliases) continue
                    runSequencesForAlias(alias, bars.getValue(alias), hub, ctx)
                }
                var deferSequenceCompletion = false
                for (alias in group.aliases) {
                    if (alias in basketAliases) continue
                    deferSequenceCompletion =
                        fireRulesForAlias(alias, bars.getValue(alias), hub, ctx, emit) ||
                        deferSequenceCompletion
                }
                sequenceRuntime.afterRulePass(deferSequenceCompletion)
            }
        }

        bindBaskets(hub, ctx, emit)
    }

    /**
     * Wire each basket's implicit sync group over its constituents. When the constituents'
     * same-window bars assemble, the compositor folds them into the composite index; the
     * resulting candle is published into the hub under the basket key, then the basket runs
     * its own update-then-fire (indicators, then rules) on that synthesized close — the same
     * two-pass the explicit sync path uses. `null` (the first aligned window, baseline only)
     * publishes nothing, so `basket.close` stays Undefined until the basket is warm.
     */
    private fun bindBaskets(
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        for (basket in baskets) {
            val basketKey = streams.getValue(basket.alias)
            val compositor = BasketCompositor(basketKey.qktSymbol, basket.constituents)
            val members = basket.constituents.associateWith { streams.getValue(it) }
            val groupKey = SyncGroupKey(members = members, timeoutMs = null)
            hub.registerSyncGroup(groupKey, ctx.strategyId)
            hub.onSyncClosed(groupKey, ctx.strategyId) { bars ->
                val composite = compositor.onAligned(bars) ?: return@onSyncClosed
                hub.publish(basketKey, composite)
                updatePerAlias(basket.alias, composite, hub, ctx)
                runSequencesForAlias(basket.alias, composite, hub, ctx)
                val deferSequenceCompletion = fireRulesForAlias(basket.alias, composite, hub, ctx, emit)
                sequenceRuntime.afterRulePass(deferSequenceCompletion)
            }
        }
    }

    private fun validateMetaRefs(ctx: StrategyContext) {
        val registry = ctx.instruments
        val missing = metaRefs.firstOrNull { registry.lookup(it.qktSymbol) == null }
        if (missing != null) {
            error(
                "Strategy '${ctx.strategyId}' references '${missing.stream}.${missing.field}' " +
                    "but no InstrumentMeta is registered for ${missing.qktSymbol}. " +
                    "Populate it via the MT5 broker connection (live) or a YAML manifest " +
                    "in qkt.config.yaml (backtest).",
            )
        }
    }

    private fun evaluate(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        updatePerAlias(alias, candle, hub, ctx)
        runSequencesForAlias(alias, candle, hub, ctx)
        val deferSequenceCompletion = fireRulesForAlias(alias, candle, hub, ctx, emit)
        sequenceRuntime.afterRulePass(deferSequenceCompletion)
    }

    /**
     * Per-alias close updates: warmup gate, position transitions, indicator updates,
     * rolling snapshot capture, aggregate updates. No rule firing — see
     * [fireRulesForAlias]. Split out so a sync group can run this for every member
     * before any rule fires on any member (#45).
     */
    private fun updatePerAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        warmupReplay: Boolean = false,
    ) {
        warmupGate.onClosedCandle(alias)

        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = alias,
                evaluationTimeMs = candle.endTime,
                historyAsOfMs = candle.endTime.takeIf { warmupReplay },
            )

        val symbol = streams[alias]!!.qktSymbol

        if (!warmupReplay) {
            val qty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            val transition = transitions.observe(symbol, qty)
            when (transition) {
                PositionTransition.ClosedToZero, PositionTransition.Flipped -> {
                    for (name in plan.captureOnOpen) snapshotStore.clearSlot(alias, name, SnapshotOpen)
                    aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
                }
                PositionTransition.OpenedFromZero ->
                    aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
                PositionTransition.Stay -> {}
            }
        }

        bindings.updateForAlias(alias, ec)

        for ((name, _) in plan.rollingMaxN) {
            val rhs = letCompiledRhs[name] ?: continue
            val v = rhs.evaluate(ec)
            snapshotStore.pushRolling(alias, name, if (v is Value.Num) v.v else null)
        }

        for (b in aggregates.bindingsForAlias(alias)) {
            if (b.window is SinceOpen) {
                if (warmupReplay) continue
                val curQty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }
    }

    // Rules grouped by their alias — fireRulesForAlias runs per bar close, and scanning every
    // rule with a string compare to find the alias's few was per-bar overhead.
    private val rulesByAlias: Map<String, List<CompiledRule>> by lazy { rules.groupBy { it.ruleAlias } }

    private fun runSequencesForAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
    ) {
        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = alias,
                evaluationTimeMs = candle.endTime,
                sequences = sequenceRuntime,
            )
        sequenceRuntime.onCandle(candle, ec, streamAlias = alias) { aliases -> warmupGate.isWarm(aliases) }
    }

    /**
     * Fire every rule whose `ruleAlias` matches [alias] and whose referenced streams
     * are warm. Must run after [updatePerAlias] so the rule body sees current state.
     */
    private fun fireRulesForAlias(
        alias: String,
        candle: Candle,
        hub: CandleHub,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ): Boolean {
        val aliasRules = rulesByAlias[alias] ?: return false
        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = alias,
                evaluationTimeMs = candle.endTime,
                sequences = sequenceRuntime,
            )
        var consumerFired = false
        var consumerAccepted = false
        for (rule in aliasRules) {
            if (!warmupGate.isWarm(rule.referencedAliases)) continue
            when (fireAndCommit(rule, ec, ctx, emit)) {
                SequenceFireOutcome.ACCEPTED -> {
                    consumerFired = true
                    consumerAccepted = true
                }
                SequenceFireOutcome.SUPPRESSED -> consumerFired = true
                SequenceFireOutcome.NOT_CONSUMING -> Unit
            }
        }
        sequenceRuntime.persistRuleEdges()
        return consumerFired && !consumerAccepted
    }

    /**
     * Fires [rule] and seals its edge from the submission outcome: an edge whose signals
     * were all recorded as suppressed (none accepted) re-arms and may fire again next
     * bar; anything else — an accepted signal, a signal-less fire, or a consumer that
     * records no outcomes at all — consumes the edge. See [CompiledRule.commitFire].
     */
    private fun fireAndCommit(
        rule: CompiledRule,
        ec: EvalContext,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ): SequenceFireOutcome {
        val fired = rule.fire(ec, ctx)
        if (fired.isEmpty()) {
            return when (rule.commitFire(true)) {
                RuleCommitOutcome.ACCEPTED ->
                    if (rule.consumesSequenceCompletion) {
                        SequenceFireOutcome.ACCEPTED
                    } else {
                        SequenceFireOutcome.NOT_CONSUMING
                    }
                RuleCommitOutcome.REARMED ->
                    if (rule.consumesSequenceCompletion) {
                        SequenceFireOutcome.SUPPRESSED
                    } else {
                        SequenceFireOutcome.NOT_CONSUMING
                    }
                null -> SequenceFireOutcome.NOT_CONSUMING
            }
        }
        val orderIds =
            fired
                .filterIsInstance<Signal.Submit>()
                .flatMap { correlationIds(it.request) }
        fired.forEach { ruleBySignal[it] = rule }
        orderIds.forEach { ruleByOrderId[it] = rule }
        val acceptedBefore = ctx.submissions.accepted
        val suppressedBefore = ctx.submissions.suppressed
        for (sig in fired) emit(sig)
        val anyAccepted = ctx.submissions.accepted > acceptedBefore
        val anySuppressed = ctx.submissions.suppressed > suppressedBefore
        val accepted = anyAccepted || !anySuppressed
        val committed = rule.commitFire(accepted)
        if (committed == RuleCommitOutcome.REARMED) {
            fired.forEach(ruleBySignal::remove)
            orderIds.forEach(ruleByOrderId::remove)
        }
        return when {
            committed == null || !rule.consumesSequenceCompletion -> SequenceFireOutcome.NOT_CONSUMING
            committed == RuleCommitOutcome.ACCEPTED -> SequenceFireOutcome.ACCEPTED
            else -> SequenceFireOutcome.SUPPRESSED
        }
    }

    // Symbol-keyed view of tick-fed indicator bindings, built on first tick — most strategies
    // have none, and iterating the streams map per tick to discover that was pure overhead.
    private val tickFedBySymbol: Map<String, List<IndicatorBinding>> by lazy {
        buildMap<String, MutableList<IndicatorBinding>> {
            for ((alias, key) in streams) {
                val tickBindings = bindings.tickFedForAlias(alias)
                if (tickBindings.isEmpty()) continue
                getOrPut(key.qktSymbol) { mutableListOf() }.addAll(tickBindings)
            }
        }
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        // Phase 25E: feed tick-fed indicators (e.g. VWAP) on every raw tick.
        // Candle-fed indicators keep updating only at candle close in [evaluate]; the
        // two paths are disjoint by indicator input kind, so there's no double-feeding.
        if (tickFedBySymbol.isEmpty()) return
        val tickBindings = tickFedBySymbol[tick.symbol] ?: return
        for (b in tickBindings) b.updateFromTick(tick)
    }

    override fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (hubBound) return
        if (candle.symbol !in subscribedSymbols) return

        for ((alias, key) in streams) {
            if (key.qktSymbol == candle.symbol) warmupGate.onClosedCandle(alias)
        }

        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                evaluationTimeMs = candle.endTime,
                sequences = sequenceRuntime,
            )

        // 1. Position transitions for this candle's symbol
        for ((alias, key) in streams) {
            val symbol = key.qktSymbol
            if (candle.symbol != symbol) continue
            val qty = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            val transition = transitions.observe(symbol, qty)
            when (transition) {
                PositionTransition.ClosedToZero, PositionTransition.Flipped -> {
                    for (name in plan.captureOnOpen) {
                        snapshotStore.clearSlot(alias, name, SnapshotOpen)
                    }
                    aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
                }
                PositionTransition.OpenedFromZero -> {
                    aggregates.bindingsForAlias(alias).forEach { it.resetIfSinceOpen() }
                }
                PositionTransition.Stay -> {}
            }
        }

        // 2. Indicators
        bindings.updateAll(ec)

        // 3. Per-candle rolling snapshot capture
        for ((name, _) in plan.rollingMaxN) {
            val rhs = letCompiledRhs[name] ?: continue
            val v = rhs.evaluate(ec)
            for ((alias, key) in streams) {
                if (key.qktSymbol != candle.symbol) continue
                snapshotStore.pushRolling(alias, name, if (v is Value.Num) v.v else null)
            }
        }

        // 4. Aggregate updates
        for (b in aggregates.all()) {
            if (b.window is SinceOpen) {
                val symbol = streams[b.ruleAlias]?.qktSymbol
                val curQty = symbol?.let { ctx.positions.positionFor(it)?.quantity } ?: BigDecimal.ZERO
                if (curQty.signum() != 0) b.update(ec)
            } else {
                b.update(ec)
            }
        }

        // 5. Sequence state machines
        sequenceRuntime.onCandle(candle, ec) { aliases -> warmupGate.isWarm(aliases) }

        // 6. Rules
        var consumerFired = false
        var consumerAccepted = false
        for (rule in rules) {
            if (!warmupGate.isWarm(rule.referencedAliases)) continue
            when (fireAndCommit(rule, ec, ctx, emit)) {
                SequenceFireOutcome.ACCEPTED -> {
                    consumerFired = true
                    consumerAccepted = true
                }
                SequenceFireOutcome.SUPPRESSED -> consumerFired = true
                SequenceFireOutcome.NOT_CONSUMING -> Unit
            }
        }
        sequenceRuntime.persistRuleEdges()
        sequenceRuntime.afterRulePass(consumerFired && !consumerAccepted)
    }

    private enum class SequenceFireOutcome {
        NOT_CONSUMING,
        ACCEPTED,
        SUPPRESSED,
    }
}

private fun correlationIds(request: com.qkt.execution.OrderRequest): List<String> =
    when (request) {
        is com.qkt.execution.OrderRequest.Bracket ->
            listOf(request.id, request.entry.id)
        is com.qkt.execution.OrderRequest.StandaloneOCO ->
            listOf(request.id) + correlationIds(request.leg1) + correlationIds(request.leg2)
        is com.qkt.execution.OrderRequest.OTO ->
            listOf(request.id) + correlationIds(request.parent)
        else -> listOf(request.id)
    }

private fun readsSequenceCompletion(expr: ExprAst): Boolean =
    when (expr) {
        is com.qkt.dsl.ast.SequenceAccessor -> expr.stage == null && expr.field == "complete"
        is com.qkt.dsl.ast.BinaryOp -> readsSequenceCompletion(expr.lhs) || readsSequenceCompletion(expr.rhs)
        is com.qkt.dsl.ast.UnaryOp -> readsSequenceCompletion(expr.arg)
        is com.qkt.dsl.ast.CmpOp -> readsSequenceCompletion(expr.lhs) || readsSequenceCompletion(expr.rhs)
        is com.qkt.dsl.ast.Crosses -> readsSequenceCompletion(expr.lhs) || readsSequenceCompletion(expr.rhs)
        is com.qkt.dsl.ast.FuncCall -> expr.args.any(::readsSequenceCompletion)
        is com.qkt.dsl.ast.IndicatorCall -> expr.args.any(::readsSequenceCompletion)
        is com.qkt.dsl.ast.Aggregate -> readsSequenceCompletion(expr.series)
        is com.qkt.dsl.ast.Between ->
            readsSequenceCompletion(expr.v) ||
                readsSequenceCompletion(expr.lo) ||
                readsSequenceCompletion(expr.hi)
        is com.qkt.dsl.ast.InList ->
            readsSequenceCompletion(expr.v) || expr.members.any(::readsSequenceCompletion)
        is com.qkt.dsl.ast.CaseWhen ->
            expr.branches.any { readsSequenceCompletion(it.first) || readsSequenceCompletion(it.second) } ||
                readsSequenceCompletion(expr.elseExpr)
        is com.qkt.dsl.ast.IsNull -> readsSequenceCompletion(expr.expr)
        else -> false
    }
