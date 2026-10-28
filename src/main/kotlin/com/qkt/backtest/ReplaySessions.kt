package com.qkt.backtest

import com.qkt.common.TradingCalendar
import java.time.Instant

/**
 * Whether a replayed symbol's venue takes orders at an instant: when the symbol is inside its session under the run's
 * [calendar], the calendar a live deploy resolves for it. The replay venues ask it before filling a resting order and
 * the [com.qkt.risk.rules.VenueSession] rule before an order is sent, so a backtest over quotes a venue prints outside
 * its trade session (Exness quotes AAPL from 06:00 New York, takes positions from 09:40) trades only where live could.
 *
 * A symbol a bar drives ([BarFills.windows]) is judged by its bar: the bar trades when any minute of it is in session.
 * Its synthetic open, extremes and close carry made-up stamps inside the bar (a daily FX bar's close is stamped 23:59
 * UTC, after the 17:00 New York close), so judging the stamp would refuse prices the venue really traded. A one-minute
 * pre-market bar is wholly outside; a coarse bar straddling the open is taken as tradable.
 *
 * Answers are cached per symbol for the minute, or the bar, they hold for: every calendar qkt has opens and closes on
 * whole minutes. One instance per replay, on the replay's thread.
 */
class ReplaySessions(
    private val calendar: TradingCalendar,
    private val barFills: BarFills = BarFills.NONE,
) {
    private class Answer(
        val fromMs: Long,
        val toMs: Long,
        val open: Boolean,
    )

    private val cache = HashMap<String, Answer>()

    /** True when the venue takes an order on [symbol] at [atMs]. */
    fun acceptsOrders(
        symbol: String,
        atMs: Long,
    ): Boolean {
        val cached = cache[symbol]
        if (cached != null && atMs >= cached.fromMs && atMs < cached.toMs) return cached.open
        val window = barFills.windows[symbol]
        val answer =
            if (window == null) {
                val from = Math.floorDiv(atMs, MINUTE_MS) * MINUTE_MS
                Answer(from, from + MINUTE_MS, inSession(symbol, from))
            } else {
                val from = window.windowStartFor(atMs)
                Answer(from, from + window.durationMs, anyMinuteInSession(symbol, from, from + window.durationMs))
            }
        cache[symbol] = answer
        return answer.open
    }

    private fun anyMinuteInSession(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Boolean {
        var t = fromMs
        while (t < toMs) {
            if (inSession(symbol, t)) return true
            t += MINUTE_MS
        }
        return false
    }

    private fun inSession(
        symbol: String,
        atMs: Long,
    ): Boolean = calendar.isInSession(symbol, Instant.ofEpochMilli(atMs))

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
