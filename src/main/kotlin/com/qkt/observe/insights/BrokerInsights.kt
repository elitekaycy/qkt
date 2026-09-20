package com.qkt.observe.insights

import com.qkt.broker.BrokerDeal
import com.qkt.events.BrokerEvent
import java.math.BigDecimal

/**
 * Insights translation for broker-side events: position reconciliation, balance updates,
 * gateway reachability, connection state changes, and executed venue deals. Mixed into
 * [InsightsTranslate]; pure, allocation limited to the payload map each envelope carries.
 */
interface BrokerInsights {
    fun fromPositionReconciled(e: BrokerEvent.PositionReconciled): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            null,
            "position.reconciled",
            mapOf(
                "symbol" to e.symbol,
                "before" to (e.oldQty ?: BigDecimal.ZERO),
                "after" to e.newQty,
                "oldQty" to e.oldQty,
                "newQty" to e.newQty,
                "oldAvgPx" to e.oldAvgPx,
                "newAvgPx" to e.newAvgPx,
                "source" to e.source,
                "reason" to e.reason,
            ),
        )

    fun fromBalancesUpdated(e: BrokerEvent.BalancesUpdated): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            null,
            "balances.updated",
            mapOf(
                "balances" to e.balances,
                "source" to e.source,
            ),
        )

    fun fromGatewayUnreachable(e: BrokerEvent.GatewayUnreachable): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            null,
            "gateway.unreachable",
            mapOf("detail" to "${e.broker} unreachable after ${e.consecutiveFailures} consecutive failures"),
        )

    fun fromBrokerGatewayUnreachable(e: BrokerEvent.GatewayUnreachable): InsightsEnvelope =
        busEnvelope(
            e.sequenceId,
            e.timestamp,
            null,
            "broker.disconnected",
            mapOf(
                "broker" to e.broker,
                "consecutiveFailures" to e.consecutiveFailures,
                "reason" to "gateway-unreachable",
                "ts" to e.timestamp,
            ),
        )

    fun fromBrokerConnectionChanged(e: BrokerEvent.ConnectionChanged): InsightsEnvelope {
        val type =
            when (e.state) {
                BrokerEvent.ConnectionState.CONNECTED -> "broker.connected"
                BrokerEvent.ConnectionState.DISCONNECTED -> "broker.disconnected"
                BrokerEvent.ConnectionState.RECONNECTED -> "broker.reconnected"
            }
        return busEnvelope(
            e.sequenceId,
            e.timestamp,
            null,
            type,
            mapOf(
                "broker" to e.broker,
                "state" to e.state.name.lowercase(),
                "reason" to e.reason,
                "consecutiveFailures" to e.consecutiveFailures,
                "ts" to e.timestamp,
            ),
        )
    }

    fun brokerConnected(
        broker: String,
        ts: Long,
        reason: String = "session-start",
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "broker-connected-$broker-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "broker.connected",
            payload = mapOf("broker" to broker, "state" to "connected", "reason" to reason, "ts" to ts),
        )

    /**
     * One executed venue deal ("broker.deal"). Deterministic id from the broker plus
     * deal ticket, so re-sending the same deal (restart re-backfill, retried batch)
     * dedupes at the collector instead of double-counting realized P&L.
     */
    fun brokerDeal(
        d: BrokerDeal,
        strategyId: String?,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "deal-${d.broker}-${d.dealTicket}",
            seq = 0,
            ts = d.ts,
            strategyId = strategyId,
            type = "broker.deal",
            payload =
                mapOf(
                    "broker" to d.broker,
                    "dealTicket" to d.dealTicket,
                    "positionTicket" to d.positionTicket,
                    "orderTicket" to d.orderTicket,
                    "symbol" to d.symbol,
                    "side" to d.side.name,
                    "entry" to d.entry,
                    "qty" to d.qty,
                    "price" to d.price,
                    "profit" to d.profit,
                    "commission" to d.commission,
                    "swap" to d.swap,
                    "fee" to d.fee,
                    "clientOrderId" to d.clientOrderId,
                    "magic" to d.magic,
                    "comment" to d.comment,
                    "ts" to d.ts,
                    "strategyId" to strategyId,
                ),
        )
}
