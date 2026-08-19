package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.research.ReplayEngine
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
    private val engine: ReplayEngine,
    private val bridges: Map<String, BotBridgeStrategy>,
    private val history: BarHistory,
    private val recorder: BotSessionRecorder,
) {
    /**
     * Advances replay until the next closed bar for [symbol] and returns it, or null
     * when the data window is exhausted. Ticks inside the bar are replayed in full,
     * so fills, exits, and risk evaluate at normal fidelity.
     */
    fun next(symbol: String): Candle? {
        val before = history.countFor(symbol)
        engine.advanceUntil { history.countFor(symbol) > before || engine.exhausted }
        if (history.countFor(symbol) == before) return null
        return history.last(symbol, 1).firstOrNull()
    }

    /** Newest [count] closed bars up to sim-now, oldest-first. Never looks ahead. */
    fun bars(
        symbol: String,
        count: Int,
    ): List<Candle> = history.last(symbol, count)

    /** Last tick at the replay cursor, or null before the first tick of [symbol]. */
    fun quote(symbol: String): Tick? = recorder.lastTick(symbol)

    /** Replay cursor in UTC epoch millis. */
    fun simNowMs(): Long = engine.currentTimestamp

    /** Account equity at the cursor (starting balance + realized + unrealized). */
    fun equity(): BigDecimal = engine.equity()

    /** Open positions at the cursor, keyed by symbol. */
    fun positions(): Map<String, com.qkt.positions.Position> = engine.openPositions()

    /** Declared identities orders may attribute to. */
    fun identities(): Set<String> = bridges.keys

    /**
     * Enqueues [signal] on [identity]'s bridge; it enters the pipeline on the next
     * tick replayed (the first tick after the client's current cursor position).
     * Fail-closed on an undeclared identity.
     */
    fun submit(
        identity: String,
        signal: Signal,
    ) {
        val bridge =
            requireNotNull(bridges[identity]) {
                "unknown identity '$identity' — declared identities: ${bridges.keys.sorted().joinToString(", ")}"
            }
        bridge.submit(signal)
    }

    /**
     * Ends the run at the current cursor and returns the standard backtest result
     * (the caller writes report artifacts). The recorder's reserved id is filtered
     * from per-strategy rows.
     */
    fun finish(): BacktestResult {
        val result = engine.snapshot()
        return result.copy(perStrategy = result.perStrategy.filterKeys { it != BotSessionRecorder.ID })
    }
}
