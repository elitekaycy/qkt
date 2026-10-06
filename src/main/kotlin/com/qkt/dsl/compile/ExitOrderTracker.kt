package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Remembers which market orders a rule sent as exits, those that reduced the strategy's position
 * when the rule fired, so one the venue ends unfilled or part-filled re-arms that rule
 * ([CompiledRule.rearmForExitRetry]) instead of leaving the position open for good (#1359). Counts
 * the rule's consecutive failed fires; a full fill of its exit, or a fresh fire that is not a retry,
 * resets the count. Entries are never tracked, so a cancelled entry is never resent.
 */
internal class ExitOrderTracker {
    /** One exit order: the rule that sent it, the symbol it reduces, and the fire it came from. */
    class Sent(
        val rule: CompiledRule,
        val symbol: String,
        val fire: Long,
        val positions: StrategyPositionView,
    )

    // Bounded: an exit suppressed before the venue never ends there, so its entry would linger.
    private val byOrderId =
        object : LinkedHashMap<String, Sent>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sent>?) = size > CAPACITY
        }
    private val failures = HashMap<CompiledRule, Int>()
    private val lastFailedFire = HashMap<CompiledRule, Long>()
    private var fires = 0L

    /** Number a fire of [rule]; a fire that is not a retry starts its failure count afresh. */
    fun beginFire(
        rule: CompiledRule,
        retry: Boolean,
    ): Long {
        if (!retry) forget(rule)
        return ++fires
    }

    /** [signal] as an exit of [rule]'s fire [fire], or null when it does not reduce the held position. */
    fun exitOf(
        signal: Signal,
        rule: CompiledRule,
        fire: Long,
        positions: StrategyPositionView,
    ): Sent? {
        val (symbol, side) =
            when (signal) {
                is Signal.Buy -> signal.symbol to Side.BUY
                is Signal.Sell -> signal.symbol to Side.SELL
                is Signal.Submit -> {
                    val request = signal.request as? OrderRequest.Market ?: return null
                    if (request.closesLegId != null || request.closesTicket != null) {
                        return Sent(rule, request.symbol, fire, positions)
                    }
                    request.symbol to request.side
                }
                else -> return null
            }
        return Sent(rule, symbol, fire, positions).takeIf { reduces(positions, symbol, side) }
    }

    fun track(
        clientOrderId: String,
        sent: Sent,
    ) {
        byOrderId[clientOrderId] = sent
    }

    /**
     * Exit [clientOrderId] ended cancelled or rejected without filling in full. Re-arms its rule while
     * the strategy still holds a position on the symbol; null when it was no tracked exit or nothing
     * is held any more.
     */
    fun onUnfilled(clientOrderId: String): ExitRetry? {
        val sent = byOrderId.remove(clientOrderId) ?: return null
        val held = sent.positions.positionFor(sent.symbol)?.quantity ?: BigDecimal.ZERO
        if (held.signum() == 0 && sent.positions.legsFor(sent.symbol).isEmpty()) return null
        sent.rule.rearmForExitRetry()
        if (lastFailedFire.put(sent.rule, sent.fire) != sent.fire) failures.merge(sent.rule, 1, Int::plus)
        return ExitRetry(sent.rule.ruleId, sent.symbol, failures[sent.rule] ?: 1, held)
    }

    /** Exit [clientOrderId] filled in full: its rule's failure count resets. */
    fun onFilled(clientOrderId: String) {
        byOrderId.remove(clientOrderId)?.let { forget(it.rule) }
    }

    fun clear() {
        byOrderId.clear()
        failures.clear()
        lastFailedFire.clear()
    }

    private fun forget(rule: CompiledRule) {
        failures.remove(rule)
        lastFailedFire.remove(rule)
    }

    private fun reduces(
        positions: StrategyPositionView,
        symbol: String,
        side: Side,
    ): Boolean {
        val qty = positions.positionFor(symbol)?.quantity ?: return false
        return if (side == Side.BUY) qty.signum() < 0 else qty.signum() > 0
    }

    private companion object {
        const val CAPACITY = 1024
    }
}
