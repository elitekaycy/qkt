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

    fun fromBrokerGatewayUnreachable(
        e: BrokerEvent.GatewayUnreachable,
        venue: Map<String, Any?> = emptyMap(),
    ): InsightsEnvelope =
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
            ) + venue,
        )

    /** A broker connection change; [venue] is its `venue` and `accountId` ([venuePayload]). */
    fun fromBrokerConnectionChanged(
        e: BrokerEvent.ConnectionChanged,
        venue: Map<String, Any?> = emptyMap(),
    ): InsightsEnvelope {
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
            ) + venue,
        )
    }

    fun brokerConnected(
        broker: String,
        ts: Long,
        reason: String = "session-start",
        venue: Map<String, Any?> = emptyMap(),
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "broker-connected-$broker-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "broker.connected",
            payload = mapOf("broker" to broker, "state" to "connected", "reason" to reason, "ts" to ts) + venue,
        )

    /**
     * One executed venue deal ("broker.deal"). Deterministic id from the broker plus
     * deal ticket, so re-sending the same deal (restart re-backfill, retried batch)
     * dedupes at the collector instead of double-counting realized P&L.
     *
     * Beside the venue's own fields it states what the collector must not infer: `positionId`
     * and `legEntry` (the position ticket and leg, qkt-minted where the venue names none),
     * `closeReason`, typed `costs` (`amount` positive when paid, in [accountCurrency] where the
     * venue gave only the scalar `commission`/`swap`/`fee`), `realizedSource`, and the [facts]
     * the poller resolved (`venue`, `accountId`, `attribution`, `instrument`).
     */
    fun brokerDeal(
        d: BrokerDeal,
        strategyId: String?,
        facts: Map<String, Any?> = emptyMap(),
        accountCurrency: String? = null,
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
                    "positionId" to d.positionTicket,
                    "nextPositionId" to d.nextPositionTicket,
                    "legEntry" to d.entry,
                    "closeReason" to d.closeReason,
                    "costs" to dealCosts(d, accountCurrency),
                    "realizedSource" to d.realizedSource,
                ) + facts,
        )
}

/** [d]'s typed costs, or its scalar ones (negative when paid, MT5's sign) as typed costs in [currency]. */
private fun dealCosts(
    d: BrokerDeal,
    currency: String?,
): List<Map<String, Any?>> {
    if (d.costs.isNotEmpty()) {
        return d.costs.map {
            mapOf("kind" to it.kind.name, "amount" to it.amount.amount, "currency" to it.amount.normalizedCurrency)
        }
    }
    val scalars = listOf("COMMISSION" to d.commission, "SWAP" to d.swap, "EXCHANGE_FEE" to d.fee)
    return scalars.mapNotNull { (kind, amount) ->
        amount?.takeIf { it.signum() != 0 }?.let {
            mapOf("kind" to kind, "amount" to it.negate(), "currency" to currency)
        }
    }
}
