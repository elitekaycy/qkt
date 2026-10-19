package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.OpenStructure
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext

/**
 * Actions a running compiled strategy executes on demand, outside its rules: what a `qkt bot` session
 * intent becomes. An action is compiled as a rule's action is (DEFAULTS merged, LETs resolved, the same
 * checks) and fired against the latest closed candle, the way a `SCHEDULE` fires.
 *
 * [validate] runs on any thread and touches nothing of the strategy: it compiles on a scratch compiler.
 * [fire] compiles on the strategy's own compiler and emits, so it must run on the engine's thread.
 */
interface AdHocActions {
    /** Throws, naming the reason, when [action] cannot run as an intent. */
    fun validate(action: ActionAst)

    /**
     * Compiles [action] on the strategy's compiler without firing it, as it was compiled when it first
     * fired: what an earlier run registered (exit hooks, ON FILL children) exists again before restore.
     */
    fun rebuild(action: ActionAst)

    /**
     * Compiles and fires [action] now; [AdHocFire.Skipped] when no stream has closed a bar. [onCompiled]
     * runs once the action compiled and before anything is emitted.
     */
    fun fire(
        action: ActionAst,
        ctx: StrategyContext,
        nowMs: Long,
        emit: (Signal) -> Unit,
        onCompiled: () -> Unit = {},
    ): AdHocFire
}

sealed interface AdHocFire {
    data class Fired(
        val signals: List<Signal>,
    ) : AdHocFire

    data class Skipped(
        val reason: String,
    ) : AdHocFire
}

/** What [AstCompiler] keeps of a compile so later actions compile the way its rules did. */
internal class AdHocCompileContext(
    val streams: Map<String, HubKey>,
    val defaults: DefaultsBlock?,
    val resolver: LetResolver,
    val readOnlyAliases: Set<String>,
    val structureAliases: Set<String>,
    val actionCompiler: ActionCompiler,
    /** A compiler with fresh state, for validation that must leave the strategy untouched. */
    val scratch: () -> ScratchCompiler,
)

internal class ScratchCompiler(
    val actions: ActionCompiler,
    val indicators: IndicatorBinding.Bag,
    val aggregates: AggregateBinding.Bag,
)

internal fun adHocContext(
    ast: StrategyAst,
    streams: Map<String, HubKey>,
    readOnly: Set<String>,
    baskets: Map<String, List<String>>,
    resolver: LetResolver,
    actionCompiler: ActionCompiler,
): AdHocCompileContext {
    val structures = structureAliases(ast)
    return AdHocCompileContext(streams, ast.defaults, resolver, readOnly, structures, actionCompiler) {
        val indicators = IndicatorBinding.Bag()
        val aggregates = AggregateBinding.Bag()
        val expr = ExprCompiler(indicators, aggregates, baskets, structures = StructureSupport(structures))
        val actions =
            ActionCompiler(
                expr,
                org.slf4j.LoggerFactory.getLogger("com.qkt.dsl.strategy.${ast.name}.adhoc"),
                com.qkt.common.SequentialIdGenerator(prefix = "adhoc-"),
                PendingStacks(),
                baskets,
                expr.forExitHooks(),
                ExitHookCatalog(fingerprintContext = exitHookFingerprintContext(streams, baskets)),
            )
        ScratchCompiler(actions, indicators, aggregates)
    }
}

internal class CompiledAdHocActions(
    private val context: AdHocCompileContext,
    private val snapshotStore: SnapshotStore,
    private val sequenceRuntime: SequenceRuntime,
    private val binding: StrategyHubBinding,
) : AdHocActions {
    override fun validate(action: ActionAst) {
        refuseUnsupported(action)
        val scratch = context.scratch()
        scratch.actions.compile(resolved(action), ruleAlias(action))
        require(scratch.indicators.maxWarmupBars == 0 && scratch.aggregates.all().isEmpty()) {
            "indicators are not available in an intent; compute the value and pass a number"
        }
        require(
            !scratch.actions.usesBookSizing,
        ) { "OF BOOK sizing is only for portfolio children; size by lots or equity" }
    }

    override fun rebuild(action: ActionAst) {
        context.actionCompiler.compile(resolved(action), ruleAlias(action))
    }

    override fun fire(
        action: ActionAst,
        ctx: StrategyContext,
        nowMs: Long,
        emit: (Signal) -> Unit,
        onCompiled: () -> Unit,
    ): AdHocFire {
        val hub = binding.boundHub ?: return AdHocFire.Skipped("no closed bar yet: the strategy has no candles")
        // An action on a stream prices from that stream's last bar (a trailing entry's reference, a bracket's
        // distances), never from whichever stream closed last.
        val own = targetAlias(action)
        val candle =
            if (own != null) {
                context.streams[own]?.let(hub::latest) ?: return AdHocFire.Skipped("no closed bar yet on $own")
            } else {
                binding.latestKnownCandle(hub) ?: return AdHocFire.Skipped("no closed bar yet on any stream")
            }
        val compiled = context.actionCompiler.compile(resolved(action), ruleAlias(action))
        onCompiled()
        val ec =
            EvalContext(
                candle = candle,
                streams = context.streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = own,
                evaluationTimeMs = nowMs,
                sequences = sequenceRuntime,
            )
        val signals = compiled(ec)
        signals.forEach(emit)
        return AdHocFire.Fired(signals)
    }

    private fun resolved(action: ActionAst): ActionAst =
        context.resolver.resolve(mergeDefaults(action, context.defaults))

    private fun refuseUnsupported(action: ActionAst) {
        val unknown = collectStreamAliases(WhenThen(BoolLit(true), action)) - context.streams.keys
        require(
            unknown.isEmpty(),
        ) { "Unknown stream alias: ${unknown.first()}; the session declares ${context.streams.keys}" }
        require(action !is OpenStructure && !(action is Close && action.stream in context.structureAliases)) {
            "option structures cannot be opened by an intent; trade each leg, or deploy a strategy"
        }
        require(action !is Resize) { "RESIZE is not available in an intent; buy or sell the difference" }
        require(collectStackAtSymbols(action, context.streams).isEmpty()) {
            "STACK_AT is not available in an intent; deploy a strategy"
        }
        validateCompleteBracket(action, context.defaults)
        rejectReadOnlyOrders(action, context.readOnlyAliases)
    }

    private fun targetAlias(action: ActionAst): String? =
        when (action) {
            is Buy -> action.stream
            is Sell -> action.stream
            is Close -> action.stream
            is Cancel -> action.stream
            else -> null
        }

    private fun ruleAlias(action: ActionAst): String? =
        when (action) {
            is Buy -> action.stream
            is Sell -> action.stream
            is Close -> action.stream
            is Cancel -> action.stream
            else -> context.streams.keys.firstOrNull()
        }
}
