package com.qkt.trade.session

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.compile.AdHocActions
import com.qkt.dsl.compile.AdHocFire
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** What became of one intent so far. */
data class IntentFate(
    val intentId: String,
    val identity: String,
    /** `queued`, `fired` (an order went to the pipeline) or `skipped` (none could be built). */
    val state: String,
    val orderIds: List<String> = emptyList(),
    val reason: String? = null,
    val atMs: Long? = null,
)

/**
 * One session identity: a compiled DSL strategy (by delegation, so the engine binds, warms and
 * attributes it as any strategy) whose orders come from intents instead of rules. [submit] validates
 * an intent on the caller's thread and queues it; the next tick the engine hands this strategy fires
 * every queued intent in arrival order, the way a `SCHEDULE` fires its action.
 */
class BotIntentStrategy(
    val identity: String,
    private val inner: DslCompiledStrategy,
) : DslCompiledStrategy by inner,
    com.qkt.strategy.PerStreamWarmable {
    private val actions: AdHocActions = requireNotNull(inner.adHoc) { "strategy has no ad-hoc actions" }
    private val queue = ConcurrentLinkedQueue<Pair<String, ActionAst>>()
    private val signals = ConcurrentLinkedQueue<Signal>()
    private val fateLog = CopyOnWriteArrayList<IntentFate>()

    override val perStreamWarmup: Map<com.qkt.strategy.WarmupStream, com.qkt.strategy.WarmupSpec>
        get() = (inner as? com.qkt.strategy.PerStreamWarmable)?.perStreamWarmup ?: emptyMap()

    /** Validates [action] (throwing the reason) and queues it as [intentId]. */
    fun submit(
        intentId: String,
        action: ActionAst,
    ) {
        actions.validate(action)
        fateLog += IntentFate(intentId, identity, "queued")
        queue.add(intentId to action)
    }

    /** Queues a ready-made [signal] for the next tick, outside the DSL (the hand-written parity reference). */
    fun submitSignal(signal: Signal) {
        signals.add(signal)
    }

    /** Every intent's latest fate, in arrival order. */
    fun fates(): List<IntentFate> = fateLog.groupBy { it.intentId }.map { (_, history) -> history.last() }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        inner.onTick(tick, ctx, emit)
        while (true) emit(signals.poll() ?: break)
        while (true) {
            val (intentId, action) = queue.poll() ?: return
            fateLog += fire(intentId, action, ctx, emit)
        }
    }

    private fun fire(
        intentId: String,
        action: ActionAst,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ): IntentFate {
        val now = ctx.clock.now()
        val fired =
            try {
                actions.fire(action, ctx, now, emit)
            } catch (e: RuntimeException) {
                return IntentFate(intentId, identity, "skipped", reason = e.message, atMs = now)
            }
        return when (fired) {
            is AdHocFire.Skipped -> IntentFate(intentId, identity, "skipped", reason = fired.reason, atMs = now)
            is AdHocFire.Fired -> {
                val suppressed = fired.signals.filterIsInstance<Signal.Suppressed>()
                val sent = fired.signals - suppressed.toSet()
                when {
                    sent.isNotEmpty() -> IntentFate(intentId, identity, "fired", orderIdsOf(sent), atMs = now)
                    suppressed.isNotEmpty() ->
                        IntentFate(intentId, identity, "skipped", reason = suppressed.first().reason, atMs = now)
                    else ->
                        IntentFate(
                            intentId,
                            identity,
                            "skipped",
                            reason = "the action built no order (no entry price yet, a zero size, or nothing to close)",
                            atMs = now,
                        )
                }
            }
        }
    }

    private fun orderIdsOf(signals: List<Signal>): List<String> =
        signals.mapNotNull { (it as? Signal.Submit)?.request?.id }
}

/** Compiles one [BotIntentStrategy] per identity over the session's [BotSessionStreams]. */
object BotSessionIdentities {
    fun compile(
        streams: BotSessionStreams,
        identities: List<String>,
    ): Map<String, BotIntentStrategy> =
        identities.withIndex().associate { (i, identity) ->
            val ast =
                when (val parsed = Dsl.parse(streams.source("bot$i"))) {
                    is ParseResult.Success -> parsed.value
                    is ParseResult.Failure ->
                        error(
                            "session streams failed to parse: ${parsed.errors.joinToString { it.message }}",
                        )
                }
            identity to BotIntentStrategy(identity, AstCompiler().compile(ast) as DslCompiledStrategy)
        }
}
