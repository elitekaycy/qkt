package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * A run session: the stateful heart of external-decision trading through `qkt bot`.
 *
 * Holds a paced [ReplayEngine] whose strategy slots are [BotBridgeStrategy] doors
 * (one per declared identity) plus a [BotSessionRecorder]. The external client owns
 * the loop: each [next] advances replay to the next closed bar of a symbol (the pull
 * IS the sim clock), [bars]/[quote] answer reads at the cursor with no lookahead,
 * [submit] enqueues an intent that the pipeline's risk engine admits or rejects, and
 * [finish] snapshots the standard [BacktestResult] for the report writer.
 *
 * e.g. client loop: `next` → decide → `submit(Signal.Buy)` → `next` … → `finish`.
 */
class BotRunSession(
    val runId: String,
    private val backend: BotRunBackend,
    private val bridges: Map<String, BotIntentStrategy>,
    val streams: BotSessionStreams,
    private val history: BarHistory,
    private val recorder: BotSessionRecorder,
) {
    private val intentSeq =
        java.util.concurrent.atomic
            .AtomicLong()

    /**
     * Returns the next closed bar for [symbol], or null when no further bar can
     * arrive (backtest window exhausted / live session stopped). In backtest the
     * pull advances the replay — ticks inside the bar are replayed in full, so
     * fills, exits, and risk evaluate at normal fidelity. Live, it blocks until
     * the feed closes the next bar.
     */
    fun next(symbol: String): Candle? {
        val before = history.countFor(symbol)
        if (!backend.awaitNextBar(symbol, before, history)) return null
        return history.last(symbol, 1).firstOrNull()
    }

    /** Newest [count] closed bars up to sim-now, oldest-first. Never looks ahead. */
    fun bars(
        symbol: String,
        count: Int,
    ): List<Candle> = history.last(symbol, count)

    /** Last tick at the replay cursor, or null before the first tick of [symbol]. */
    fun quote(symbol: String): Tick? = recorder.lastTick(symbol)

    /** Session time in UTC epoch millis (replay cursor, or wall clock live). */
    fun simNowMs(): Long = backend.nowMs()

    /** Model equity at the cursor; null live (equity is venue truth there). */
    fun equity(): BigDecimal? = backend.equity()

    /** Open positions at the cursor, keyed by symbol. */
    fun positions(): Map<String, com.qkt.positions.Position> = backend.positions()

    /** Declared identities orders may attribute to. */
    fun identities(): Set<String> = bridges.keys

    /**
     * Validates intent [source] for [identity] and queues it; it fires on the next tick the engine
     * replays (the first after the client's cursor). Throws the reason when it cannot run.
     */
    fun submit(
        identity: String,
        source: String,
    ): SubmittedIntent {
        val intent = sessionIntent(source, streams)
        val id = "int-${intentSeq.incrementAndGet()}"
        identity(identity).submit(id, intent.action)
        return SubmittedIntent(id, intent.symbol, intent.sha256)
    }

    /** Queues a ready-made [signal] on [identity] for the next tick (no DSL; the hand-written reference). */
    fun submit(
        identity: String,
        signal: Signal,
    ) = identity(identity).submitSignal(signal)

    /** Every intent's latest fate, in arrival order across identities. */
    fun fates(): List<IntentFate> =
        bridges.values
            .flatMap {
                it.fates()
            }.sortedBy { it.intentId.removePrefix("int-").toLong() }

    private fun identity(identity: String): BotIntentStrategy =
        requireNotNull(bridges[identity]) {
            "unknown identity '$identity' — declared identities: ${bridges.keys.sorted().joinToString(", ")}"
        }

    /**
     * Ends the run. Backtest returns the standard result (caller writes report
     * artifacts; the recorder's reserved id is filtered from per-strategy rows);
     * live stops the session and returns null.
     */
    fun finish(): BacktestResult? {
        val result = backend.finish() ?: return null
        return result.copy(perStrategy = result.perStrategy.filterKeys { it != BotSessionRecorder.ID })
    }
}

/** An accepted intent: its [intentId], the [symbol] it acts on (null for every symbol) and its source hash. */
data class SubmittedIntent(
    val intentId: String,
    val symbol: String?,
    val sha256: String,
)
