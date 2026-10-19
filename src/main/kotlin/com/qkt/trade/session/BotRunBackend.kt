package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import com.qkt.research.ReplayEngine
import java.math.BigDecimal

/**
 * What differs between a backtest session and a live session: how the next bar
 * arrives, where the clock/equity/positions come from, and what finishing yields.
 * The client-facing verbs are identical over both.
 */
interface BotRunBackend {
    /**
     * Blocks until [history] holds more than [before] bars of [symbol]. Backtest
     * advances the replay (the pull IS the sim clock); live waits for the feed.
     * Returns false when no further bar can arrive (window exhausted / stopped).
     */
    fun awaitNextBar(
        symbol: String,
        before: Long,
        history: BarHistory,
    ): Boolean

    /** Session time in UTC epoch millis (replay cursor, or wall clock live). */
    fun nowMs(): Long

    /** Account equity; null when the backend cannot compute it (live venue-based). */
    fun equity(): BigDecimal?

    /** Open positions keyed by symbol (model truth). */
    fun positions(): Map<String, com.qkt.positions.Position>

    /** Ends the run. Backtest returns the result for the report writer; live null. */
    fun finish(): BacktestResult?

    /** Runs until no position is open and [pending] is false, or [timeoutMs] passes. */
    fun awaitFlat(
        timeoutMs: Long,
        pending: () -> Boolean,
    )
}

/** Backtest backend: a paced [ReplayEngine] advanced on demand. */
class ReplayBotRunBackend(
    private val engine: ReplayEngine,
) : BotRunBackend {
    override fun awaitNextBar(
        symbol: String,
        before: Long,
        history: BarHistory,
    ): Boolean {
        engine.advanceUntil { history.countFor(symbol) > before || engine.exhausted }
        return history.countFor(symbol) > before
    }

    override fun nowMs(): Long = engine.currentTimestamp

    override fun equity(): BigDecimal = engine.equity()

    override fun positions(): Map<String, com.qkt.positions.Position> = engine.openPositions()

    override fun finish(): BacktestResult = engine.snapshot()

    override fun awaitFlat(
        timeoutMs: Long,
        pending: () -> Boolean,
    ) {
        // The close fires on the next replayed tick and fills on a later one; replay until both happened.
        if (engine.openPositions().isEmpty() && !pending()) return
        engine.advanceUntil { (engine.openPositions().isEmpty() && !pending()) || engine.exhausted }
    }
}
