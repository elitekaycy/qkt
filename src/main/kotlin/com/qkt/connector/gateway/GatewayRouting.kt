package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory

/**
 * The brokers attached to one gateway account and where each event goes: an order's events to the
 * broker of the strategy that sent it, else to a broker serving every strategy (attached with a null
 * strategy); account-wide events, a contract settlement or a connection change, to every broker.
 */
internal class GatewayRouting {
    private class Attached(
        val strategy: String?,
        val publish: (BrokerEvent) -> Unit,
    )

    private val log = LoggerFactory.getLogger(GatewayRouting::class.java)
    private val attached = CopyOnWriteArrayList<Attached>()

    /** Attaches [publish] for [strategy]; true when it is the first broker. */
    fun attach(
        strategy: String?,
        publish: (BrokerEvent) -> Unit,
    ): Pair<Boolean, () -> Boolean> {
        val entry = Attached(strategy, publish)
        attached += entry
        return (attached.size == 1) to {
            attached -= entry
            attached.isEmpty()
        }
    }

    /** Hands an order's [event] to the broker of its strategy, else to one serving every strategy. */
    fun route(event: BrokerEvent.OrderEvent) {
        val targets =
            attached.filter { it.strategy == event.strategyId }.ifEmpty {
                attached.filter {
                    it.strategy ==
                        null
                }
            }
        if (targets.isEmpty()) {
            log.warn(
                "gateway event for '{}' has no strategy session here: {}",
                event.strategyId,
                event,
            )
        }
        targets.forEach { it.publish(event) }
    }

    /** Hands an account-wide [event] to every broker. */
    fun broadcast(event: BrokerEvent) = attached.forEach { it.publish(event) }
}
