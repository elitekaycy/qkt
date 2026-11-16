package com.qkt.broker.events

import com.qkt.broker.exchange.Settlement
import com.qkt.broker.exchange.SettlementLog
import com.qkt.bus.EventBus
import com.qkt.common.Side
import com.qkt.events.ContractSettled
import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.positions.StrategyLegReads
import java.time.Instant
import java.util.PriorityQueue

/**
 * Cash settlement of expired event contracts in a backtest. Once the replay's clock reaches a traded contract's
 * `expiryMs`, its working orders are cancelled ([cancel]) and every strategy holding it is closed at the catalogued
 * outcome (1, 0, or a value between after a void) through the same [ContractSettled] path a live venue's settlement
 * takes, recorded in the run's [SettlementLog]. The money the position held is released at that instant, not at the
 * contract's close. A contract that expired held but without a recorded outcome fails the run naming it: settling at
 * the last price would book a fictitious P&L.
 *
 * Only contracts an order was placed on are tracked ([track]); each tick costs one look at the earliest expiry.
 */
internal class EventExpiry(
    private val bus: EventBus,
    private val instruments: InstrumentRegistry,
    private val positions: StrategyLegReads,
    private val settlements: SettlementLog,
    private val cancel: (orderId: String) -> Unit,
) {
    private val due = PriorityQueue<Pair<Long, String>>(compareBy({ it.first }, { it.second }))
    private val working = HashMap<String, MutableList<String>>()

    /** Watches [symbol]'s expiry and remembers [orderId] to cancel there. */
    fun track(
        symbol: String,
        orderId: String,
    ) {
        val orders = working[symbol]
        if (orders == null) {
            working[symbol] = mutableListOf(orderId)
            due.add(termsOf(symbol).expiryMs to symbol)
        } else {
            orders.add(orderId)
        }
    }

    /** Settles every tracked contract whose expiry is at or before [atMs]. */
    fun onTime(atMs: Long) {
        while (true) {
            val next = due.peek() ?: return
            if (next.first > atMs) return
            due.poll()
            settle(next.second, atMs)
        }
    }

    private fun settle(
        symbol: String,
        atMs: Long,
    ) {
        working.remove(symbol).orEmpty().forEach(cancel)
        val holders =
            positions.allByStrategy().mapNotNull { (strategyId, held) ->
                held[symbol]?.quantity?.takeIf { it.signum() != 0 }?.let { strategyId to it }
            }
        if (holders.isEmpty()) return
        val terms = termsOf(symbol)
        val outcome =
            terms.outcome
                ?: error(
                    "$symbol expired at ${Instant.ofEpochMilli(terms.expiryMs)} with no outcome in the event " +
                        "catalog of ${terms.root}; import its result with " +
                        "qkt data import <file> --as ${terms.root} --kind events",
                )
        for ((strategyId, quantity) in holders) {
            val side = if (quantity.signum() > 0) Side.SELL else Side.BUY
            settlements.record(
                Settlement(atMs, strategyId, symbol, side, quantity.abs(), outcome, deliveryPriceKnown = true),
            )
        }
        bus.publish(ContractSettled(symbol, outcome, timestamp = atMs))
    }

    private fun termsOf(symbol: String): EventTerms =
        requireNotNull(instruments.lookup(symbol)?.derivative as? EventTerms) { "$symbol has no event terms" }
}
