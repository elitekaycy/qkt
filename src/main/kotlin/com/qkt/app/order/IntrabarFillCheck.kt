package com.qkt.app.order

import com.qkt.app.IntrabarFill
import com.qkt.app.StackTracker
import com.qkt.execution.OrderRequest
import com.qkt.execution.isTerminal
import java.math.BigDecimal

/**
 * Read-only: whether a live order on [symbol] could fill within the bar range `[low, high]`.
 * Direction-aware, so a gap-open through a level still counts (a buy stop at 100 fires on a bar
 * that opens at 102). A live trailing stop always needs real ticks — its level moves with the
 * intrabar path, so the bar extremes alone cannot rule a fill out. Backs the tick-resolved fill
 * replay's decision to decode a bar's ticks; never mutates state or fires a trigger. e.g. a
 * resting buy stop at 100 with a bar `[98, 101]` -> EXTREMES; with `[96, 99]` -> SYNTHETIC.
 */
internal fun intrabarFillFor(
    book: OrderBook,
    timeExits: TimeExits,
    stacks: StackTracker,
    symbol: String,
    low: BigDecimal,
    high: BigDecimal,
    maxHalfSpread: BigDecimal = BigDecimal.ZERO,
): IntrabarFill {
    // Time-based exits (GTD expiry, TimeExit, stack deadline) fire on time, not price, so a
    // fill/cancel can land on a tick the new-extreme filter would skip. Conservatively bail to a
    // full real-tick replay whenever any is live.
    if (book.gtdDeadlines.isNotEmpty() ||
        timeExits.isNotEmpty() ||
        stacks.activeView().any { it.deadlineEpochMs != null }
    ) {
        return IntrabarFill.ALL_TICKS
    }
    val ids = book.liveIdsFor(symbol) ?: return IntrabarFill.SYNTHETIC
    // Candles aggregate mid prices, while venue triggers use ask for BUY and bid for SELL.
    // Expand the mid range by the largest observed half-spread so a level crossed only by
    // the executable quote still selects real-tick resolution.
    val executableLow = low - maxHalfSpread
    val executableHigh = high + maxHalfSpread
    var fillable = false
    for (id in ids) {
        val m = book[id] ?: continue
        if (m.state.isTerminal) continue
        when (val r = m.request) {
            is OrderRequest.Stop ->
                if (stopReached(r.side, executableLow, executableHigh, r.stopPrice)) fillable = true
            is OrderRequest.StopLimit ->
                if (stopReached(r.side, executableLow, executableHigh, r.stopPrice)) fillable = true
            is OrderRequest.Limit ->
                if (limitReached(r.side, executableLow, executableHigh, r.limitPrice)) fillable = true
            is OrderRequest.IfTouched ->
                if (limitReached(r.side, executableLow, executableHigh, r.triggerPrice)) fillable = true
            // Trailing/composite shapes (OTO, OCO, trailing stops, ...) move with the path; their
            // trigger is not a fixed level we can search for, so resolve the bar on real ticks.
            else -> return IntrabarFill.ALL_TICKS
        }
    }
    return if (fillable) IntrabarFill.EXTREMES else IntrabarFill.SYNTHETIC
}
