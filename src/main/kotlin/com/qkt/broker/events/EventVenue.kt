package com.qkt.broker.events

import com.qkt.broker.Broker
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentRegistry

/**
 * A backtest's event-contract venue: [fills] matches orders on the contracts' quotes, and [expiry] settles them. An
 * order on a contract whose trading has closed (`closeAtMs`) is refused, as the venue refuses it; every accepted order
 * is handed to [expiry] so it is cancelled if still working when the contract expires.
 */
internal class EventVenue(
    private val fills: Broker,
    private val expiry: EventExpiry,
    private val bus: EventBus,
    private val clock: Clock,
    private val instruments: InstrumentRegistry,
) : Broker by fills {
    override fun submit(request: OrderRequest): SubmitAck {
        val terms = instruments.lookup(request.symbol)?.derivative as? EventTerms
        val now = clock.now()
        if (terms != null && now >= terms.closeAtMs) {
            val reason = "${request.symbol} stopped trading at ${terms.closeAtMs}"
            bus.publish(BrokerEvent.OrderRejected(request.id, request.id, reason, request.strategyId, now))
            return SubmitAck(request.id, request.id, accepted = false, rejectReason = reason)
        }
        if (terms != null) expiry.track(request.symbol, request.id)
        return fills.submit(request)
    }
}
