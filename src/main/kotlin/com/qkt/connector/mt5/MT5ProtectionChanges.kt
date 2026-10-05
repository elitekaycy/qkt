package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

/**
 * Reports a stop or target that changed on a venue position between two polls, e.g. ticket 999's
 * stop moved from 1.0900 to 1.0950 by hand in the terminal. A change the engine asked for itself
 * ([isExpectedProtectionChange]) is not reported.
 */
internal class MT5ProtectionChanges(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
    private val closedTicketMeta: ((Long) -> ClosedPositionMeta?)?,
    private val isExpectedProtectionChange: ((BrokerEvent.PositionProtectionChanged) -> Boolean)?,
) {
    private val log = LoggerFactory.getLogger(MT5PositionPoller::class.java)

    fun report(
        previous: MT5Position,
        latest: MT5Position,
        now: Long,
    ) {
        if (previous.sl.compareTo(latest.sl) == 0 && previous.tp.compareTo(latest.tp) == 0) return
        val event =
            BrokerEvent.PositionProtectionChanged(
                broker = profile.name,
                symbol = "${profile.name.uppercase()}:${symbol.toQkt(latest.symbol)}",
                ticket = latest.ticket.toString(),
                oldStopLoss = previous.sl,
                newStopLoss = latest.sl,
                oldTakeProfit = previous.tp,
                newTakeProfit = latest.tp,
                strategyId = closedTicketMeta?.invoke(latest.ticket)?.strategyId ?: "",
                timestamp = now,
            )
        if (isExpectedProtectionChange?.invoke(event) == true) return
        log.error(
            "MT5 position protection changed broker={} ticket={} sl={}->{} tp={}->{}",
            profile.name,
            latest.ticket,
            previous.sl,
            latest.sl,
            previous.tp,
            latest.tp,
        )
        bus.publish(event)
    }
}
