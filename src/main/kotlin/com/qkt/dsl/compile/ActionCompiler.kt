package com.qkt.dsl.compile

import com.qkt.common.IdGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExitHooksAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Compiles a rule's `THEN` actions into closures that emit [Signal]s when the rule fires. This
 * class is the dispatch point: each action kind compiles in its own collaborator
 * ([EntryOrderCompiler], [StackActionCompiler], [OtoActionCompiler], [CloseActionCompiler], ...),
 * all sharing one id generator, sizing compiler and exit-hook catalog.
 */
class ActionCompiler(
    private val exprCompiler: ExprCompiler,
    private val strategyLogger: Logger = LoggerFactory.getLogger("com.qkt.dsl.strategy"),
    private val ids: IdGenerator = SequentialIdGenerator(prefix = "dsl-anonymous-"),
    private val pendingStacks: PendingStacks? = null,
    private val baskets: Map<String, List<String>> = emptyMap(),
    exitExprCompiler: ExprCompiler? = null,
    private val exitHookCatalog: ExitHookCatalog = ExitHookCatalog(),
) {
    private val exitExprCompiler = exitExprCompiler ?: exprCompiler.forExitHooks()
    private val orderTypeCompiler = OrderTypeCompiler(exprCompiler)
    private val childPriceResolver = ChildPriceResolver(exprCompiler)
    private val childPriceFreezer = ChildPriceFreezer(exprCompiler)
    private val sizingCompiler = SizingCompiler(exprCompiler)
    private val latchCompiler = LatchCompiler(exprCompiler, sizingCompiler, ids)
    private val entryOrders =
        EntryOrderCompiler(
            exprCompiler,
            strategyLogger,
            ids,
            pendingStacks,
            orderTypeCompiler,
            childPriceResolver,
            childPriceFreezer,
            sizingCompiler,
        )
    private val stacks = StackActionCompiler(childPriceResolver, childPriceFreezer, sizingCompiler, ids)
    private val otos = OtoActionCompiler(orderTypeCompiler, sizingCompiler, ids, baskets, strategyLogger)
    private val basketFanOuts = BasketFanOutCompiler(exprCompiler)
    private val repeats = RepeatedActionCompiler(exprCompiler, strategyLogger)
    private val ocoEntries = OcoEntryCompiler(ids, strategyLogger)
    private val closes = CloseActionCompiler(ids, baskets)
    private val resizes = ResizeActionCompiler(exprCompiler, sizingCompiler, ids)
    private val logs = LogActionCompiler(exprCompiler, strategyLogger)

    /** True once any compiled action (or latch entry) sized `RISK … OF BOOK`. */
    val usesBookSizing: Boolean get() = sizingCompiler.compiledBookSizing

    /**
     * [ruleAlias] is the stream the enclosing rule evaluates on. Expressions that keep per-bar
     * state keyed to that stream (a `SINCE` aggregate, and so `count(x, N)`) need it to know
     * which bars to fold; without it they refuse to compile.
     */
    fun compile(
        action: ActionAst,
        ruleAlias: String? = null,
    ): (EvalContext) -> List<Signal> =
        when (action) {
            is Buy -> compileBuySell(action.stream, action.opts, Side.BUY)
            is Sell -> compileBuySell(action.stream, action.opts, Side.SELL)
            is Log -> logs.compile(action, ruleAlias)
            is Close -> closes.compileClose(action.stream)
            is CloseAll -> closes.compileCloseAll()
            is Cancel -> compileCancel(action.stream)
            is CancelAll -> compileCancelAll()
            is Block -> compileBlock(action, ruleAlias)
            is OcoEntry -> ocoEntries.compile(action, this)
            is Latch -> { ec ->
                listOf(Signal.ArmLatch(latchCompiler.compile(action, ec.strategyContext.strategyId), ec))
            }
            is Resize -> resizes.compile(action)
            else -> error("Action ${action::class.simpleName} is not supported in 11d1")
        }

    private fun compileBlock(
        action: Block,
        ruleAlias: String?,
    ): (EvalContext) -> List<Signal> {
        val children = action.actions.map { compile(it, ruleAlias) }
        return { ctx ->
            val out = mutableListOf<Signal>()
            for (child in children) out.addAll(child(ctx))
            out
        }
    }

    private fun compileCancel(streamAlias: String): (EvalContext) -> List<Signal> =
        { ctx ->
            val symbol = ctx.streams[streamAlias]?.qktSymbol ?: error("Unknown stream alias: $streamAlias")
            listOf(Signal.CancelPendingForSymbol(symbol))
        }

    private fun compileCancelAll(): (EvalContext) -> List<Signal> =
        { ctx ->
            ctx.streams.values
                .map { it.qktSymbol }
                .distinct()
                .map { Signal.CancelPendingForSymbol(it) }
        }

    companion object {
        /** Upper bound on one `TIMES` evaluation; a larger count is suppressed, not sent. */
        val MAX_TIMES: BigDecimal = BigDecimal(1000)
    }

    private fun compileBuySell(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        if (opts.times != null) {
            return repeats.compile(stream, opts.times, compileBuySell(stream, opts.copy(times = null), side))
        }
        if (!opts.exitHooks.isEmpty()) {
            return compileWithExitHooks(stream, opts, side)
        }
        baskets[stream]?.let { constituents ->
            return basketFanOuts.compile(stream, constituents, opts, side)
        }

        // OTO path: a parent with ON_FILL children placed only when the parent fills.
        if (opts.onFill.isNotEmpty()) {
            return otos.compile(stream, opts, side)
        }

        // Stack path: STACK is mutually exclusive with BRACKET/OCO on the same action.
        if (opts.stack != null) {
            // Phase 27: STACK_AT cannot combine with STACK pyramiding — the runtime
            // would silently drop the conditional clauses. Reject loudly at compile time.
            require(opts.stackAts.isEmpty()) {
                "STACK_AT cannot be combined with STACK on the same action"
            }
            return stacks.compile(stream, opts, side)
        }

        return entryOrders.compile(stream, opts, side)
    }

    private fun compileWithExitHooks(
        stream: String,
        opts: ActionOpts,
        side: Side,
    ): (EvalContext) -> List<Signal> {
        ExitHookAttachment.validate(opts)
        val childCompiler =
            ActionCompiler(
                exprCompiler = exitExprCompiler,
                strategyLogger = strategyLogger,
                ids = ids,
                pendingStacks = pendingStacks,
                baskets = baskets,
                exitExprCompiler = exitExprCompiler,
                exitHookCatalog = exitHookCatalog,
            )
        val ref = exitHookCatalog.register(opts.exitHooks, childCompiler::compile)
        val base =
            compileBuySell(
                stream,
                opts.copy(exitHooks = ExitHooksAst()),
                side,
            )
        return ExitHookAttachment.attach(base, ref)
    }
}
