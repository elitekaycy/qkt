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

/**
 * One session identity: a compiled DSL strategy (by delegation, so the engine binds, warms and
 * attributes it as any strategy) whose orders come from intents instead of rules. [submit] validates
 * an intent on the caller's thread and queues it; the next tick the engine hands this strategy fires
 * every queued intent in arrival order, the way a `SCHEDULE` fires its action.
 */
class BotIntentStrategy(
    val identity: String,
    private val inner: DslCompiledStrategy,
    internal val ledger: IntentLedger = IntentLedger(),
) : DslCompiledStrategy by inner,
    com.qkt.strategy.PerStreamWarmable {
    private val actions: AdHocActions = requireNotNull(inner.adHoc) { "strategy has no ad-hoc actions" }
    private val queue = ConcurrentLinkedQueue<Pair<String, ActionAst>>()
    private val signals = ConcurrentLinkedQueue<Signal>()
    private val sentBy = java.util.IdentityHashMap<Signal, String>()

    @Volatile private var lastContext: StrategyContext? = null

    override val perStreamWarmup: Map<com.qkt.strategy.WarmupStream, com.qkt.strategy.WarmupSpec>
        get() = (inner as? com.qkt.strategy.PerStreamWarmable)?.perStreamWarmup ?: emptyMap()

    /** Throws the reason when [action] cannot run as an intent. */
    fun validate(action: ActionAst) = actions.validate(action)

    /** Validates [action] and queues it as [intentId]. */
    fun submit(
        intentId: String,
        action: ActionAst,
    ) {
        validate(action)
        enqueue(intentId, action)
    }

    /** Queues an already validated [action] as [intentId]. */
    fun enqueue(
        intentId: String,
        action: ActionAst,
    ) {
        ledger.fate(IntentFate(intentId, identity, "queued"))
        queue.add(intentId to action)
    }

    /** Recompiles a journaled intent's [action] without firing it (a restart over this identity's state). */
    fun rebuild(action: ActionAst) = actions.rebuild(action)

    /** Queues a ready-made [signal] for the next tick, outside the DSL (the hand-written parity reference). */
    fun submitSignal(signal: Signal) {
        signals.add(signal)
    }

    /** This identity's open legs as of the last tick it saw. */
    fun legs(): List<SessionLeg> {
        val positions = lastContext?.positions ?: return emptyList()
        return positions.allPositions().keys.flatMap { symbol ->
            positions.legsFor(symbol).map {
                SessionLeg(identity, symbol, it.side.name, it.quantity, it.entryPrice, it.brokerTicket, it.legId)
            }
        }
    }

    override fun onOrderSubmitted(
        signal: Signal,
        clientOrderId: String,
    ): com.qkt.dsl.compile.DecisionOrderLink? {
        synchronized(sentBy) { sentBy.remove(signal) }?.let { intentId ->
            // A nested request's orders (a timed bracket's entry, say) are linked as well as the request.
            val nested = (signal as? Signal.Submit)?.request?.let(::requestIds) ?: emptyList()
            (listOf(clientOrderId) + nested).distinct().forEach { ledger.link(intentId, it) }
        }
        return inner.onOrderSubmitted(signal, clientOrderId)
    }

    override fun onOrderOutcome(outcome: com.qkt.strategy.OrderOutcome) {
        ledger.outcome(identity, outcome)
        inner.onOrderOutcome(outcome)
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        lastContext = ctx
        inner.onTick(tick, ctx, emit)
        while (true) emit(signals.poll() ?: break)
        while (true) {
            val (intentId, action) = queue.poll() ?: return
            val tagged: (Signal) -> Unit = { sig ->
                synchronized(sentBy) { sentBy[sig] = intentId }
                emit(sig)
            }
            ledger.fate(fire(intentId, action, ctx, tagged).copy(tickMs = tick.timestamp))
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
                actions.fire(action, ctx, now, emit) { ledger.markCompiled(intentId) }
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
        ledger: IntentLedger = IntentLedger(),
        history: BarHistory? = null,
    ): Map<String, BotIntentStrategy> =
        compileAll(streams, identities, ledger).also { compiled ->
            // The first identity's hub builds every series; its closes are the session's bars.
            val recorder = compiled.values.firstOrNull() ?: return@also
            if (history != null) {
                recorder.observeCandleEvaluations { alias, _, candle, _ ->
                    streams.seriesOf(alias)?.let { history.record(it, candle) }
                }
            }
        }

    private fun compileAll(
        streams: BotSessionStreams,
        identities: List<String>,
        ledger: IntentLedger,
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
            identity to BotIntentStrategy(identity, AstCompiler().compile(ast) as DslCompiledStrategy, ledger)
        }
}
