package com.qkt.cli.daemon.portfolio

import com.qkt.common.Clock
import java.util.IdentityHashMap
import org.slf4j.LoggerFactory

/**
 * Closes what fills after a book's halt. The halt's one close covers what a child held when its engine reached it; an
 * entry already in flight fills later and opens a leg no close was sent for, e.g. a halt at 18:00:27 with 0.4156 ETH
 * filling at 18:00:43.
 *
 * Judged from what the child's own ledger says on each heartbeat, not from a list of orders taken at the halt, so a
 * fill nobody tracked is still seen. A leg with a close working does not count, so no close is ever doubled and no
 * opposite order stacks. Closes go [FIRST_GAP_MS] after the last, doubling each time, at most [MAX_CLOSES] times
 * while the child stays unflat, then the leg is left to the operator with an error (a stopped stream or a shut market
 * refuses every close). A child seen with nothing unclosed starts afresh. A child no longer halted, which an
 * operator resumed on its own, is left to trade.
 */
internal class LateFillCloser(
    private val clock: Clock,
) {
    private class Closes(
        var count: Int,
        var nextAtMs: Long,
    )

    private val log = LoggerFactory.getLogger(LateFillCloser::class.java)
    private val closes = IdentityHashMap<ChildRiskTarget, Closes>()

    /** The book just sent [child] its close: anything closed again waits from now. */
    fun closed(child: ChildRiskTarget) {
        closes[child] = Closes(0, clock.now() + FIRST_GAP_MS)
    }

    /** One heartbeat of a halted book. */
    fun sweep(children: List<ChildRiskTarget>) {
        for (child in children) {
            if (!child.isHalted()) {
                closes.remove(child)
                continue
            }
            val unclosed =
                runCatching { child.unclosedLegs() }
                    .onFailure { log.warn("halted book: a child did not say what it holds: {}", it.message) }
                    .getOrNull() ?: continue
            closeWhatFilled(child, unclosed)
        }
    }

    private fun closeWhatFilled(
        child: ChildRiskTarget,
        unclosed: Int,
    ) {
        val now = clock.now()
        if (unclosed == 0) {
            closes[child]?.let {
                it.count = 0
                it.nextAtMs = minOf(it.nextAtMs, now + FIRST_GAP_MS)
            }
            return
        }
        val state = closes.getOrPut(child) { Closes(0, now) }
        if (now < state.nextAtMs || state.count > MAX_CLOSES) return
        if (state.count == MAX_CLOSES) {
            state.count++
            log.error("halted book: {} leg(s) still held after {} closes; close at the venue", unclosed, MAX_CLOSES)
            return
        }
        state.nextAtMs = now + (FIRST_GAP_MS shl (state.count + 1))
        state.count++
        log.error("halted book: {} leg(s) filled after the close; closing again ({})", unclosed, state.count)
        runCatching { child.flatten() }.onFailure { log.warn("child flatten failed: {}", it.message) }
    }

    /** Forgets every child: the book resumed. */
    fun clear() = closes.clear()

    companion object {
        const val FIRST_GAP_MS: Long = 5_000L
        const val MAX_CLOSES: Int = 5
    }
}
