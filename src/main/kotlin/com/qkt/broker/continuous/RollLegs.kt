package com.qkt.broker.continuous

import com.qkt.events.BrokerEvent

/** How one market leg of a roll ended. */
internal sealed interface LegOutcome {
    data class Filled(
        val fill: BrokerEvent.OrderFilled,
    ) : LegOutcome

    data class Rejected(
        val reason: String,
    ) : LegOutcome
}

/**
 * The venue orders a roll places or cancels, which the engine must never see: the market legs,
 * captured as they end, and the resting orders being cancelled before their re-placement.
 */
internal class RollLegs {
    private val pending = HashSet<String>()
    private val ended = HashMap<String, LegOutcome>()
    private val cancelling = HashSet<String>()

    /** Expect a leg under [venueId]. */
    fun expect(venueId: String) {
        pending += venueId
    }

    /** Expect the cancel of the resting order working under [venueId]. */
    fun cancelling(venueId: String) {
        cancelling += venueId
    }

    /** Whether [venueId] is a roll leg, whose events are not the engine's. */
    fun isLeg(venueId: String): Boolean = venueId in pending

    /** Capture [e] if it fills a leg; returns whether it did. */
    fun onFilled(e: BrokerEvent.OrderFilled): Boolean = end(e.clientOrderId, LegOutcome.Filled(e))

    /** Capture [e] if it rejects a leg; returns whether it did. */
    fun onRejected(e: BrokerEvent.OrderRejected): Boolean = end(e.clientOrderId, LegOutcome.Rejected(e.reason))

    /** Swallow [e] if it is a roll's cancel of a resting order; returns whether it was. */
    fun onCancelled(e: BrokerEvent.OrderCancelled): Boolean = cancelling.remove(e.clientOrderId)

    /** How the leg under [venueId] ended; the venue answers a market order before its submit returns. */
    fun outcome(venueId: String): LegOutcome {
        pending -= venueId
        return requireNotNull(ended.remove(venueId)) { "roll leg $venueId got no answer from the venue" }
    }

    private fun end(
        venueId: String,
        outcome: LegOutcome,
    ): Boolean {
        if (venueId !in pending) return false
        ended[venueId] = outcome
        return true
    }
}
