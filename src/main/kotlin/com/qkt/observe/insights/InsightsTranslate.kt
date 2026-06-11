package com.qkt.observe.insights

import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TradeEvent
import com.qkt.positions.PositionLeg
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Translates qkt bus events into [InsightsEnvelope]s matching the collector's contract.
 * Pure functions, no I/O — cheap enough for the engine thread. Returns null for event
 * shapes the contract has no representation for (e.g. a latch-arm signal).
 *
 * Envelope ids derive from the bus sequenceId ("e42"), which is unique per instance,
 * so a re-sent batch dedupes at the collector instead of double-counting.
 */
object InsightsTranslate {
    fun fromSignal(e: SignalEvent): InsightsEnvelope? {
        val (symbol, side, strategyId) =
            when (val s = e.signal) {
                is Signal.Buy -> Triple(s.symbol, "BUY", null)
                is Signal.Sell -> Triple(s.symbol, "SELL", null)
                is Signal.Submit -> Triple(s.request.symbol, s.request.side.name, s.request.strategyId)
                else -> return null
            }
        return envelope(e.sequenceId, e.timestamp, strategyId, "signal", mapOf("symbol" to symbol, "side" to side))
    }

    fun fromOrderSubmit(e: OrderEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.request.strategyId,
            "order.submit",
            mapOf(
                "orderId" to e.request.id,
                "orderType" to e.request.javaClass.simpleName,
                "symbol" to e.request.symbol,
                "side" to e.request.side.name,
                "qty" to e.request.quantity,
            ),
        )

    fun fromOrderAccepted(e: BrokerEvent.OrderAccepted): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.accepted",
            mapOf("orderId" to e.clientOrderId, "brokerOrderId" to e.brokerOrderId.orEmpty()),
        )

    fun fromOrderFilled(e: BrokerEvent.OrderFilled): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.filled",
            mapOf(
                "orderId" to e.clientOrderId,
                "brokerOrderId" to e.brokerOrderId,
                "symbol" to e.symbol,
                "price" to e.price,
                "qty" to e.quantity,
                "venueCosts" to e.venueCosts,
            ),
        )

    fun fromOrderPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.partially_filled",
            mapOf(
                "orderId" to e.clientOrderId,
                "symbol" to e.symbol,
                "price" to e.price,
                "qty" to e.quantity,
                "cumulativeQty" to e.cumulativeFilled,
            ),
        )

    fun fromOrderCancelled(e: BrokerEvent.OrderCancelled): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.cancelled",
            mapOf("orderId" to e.clientOrderId, "reason" to e.reason),
        )

    fun fromOrderRejected(e: BrokerEvent.OrderRejected): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.rejected",
            mapOf("orderId" to e.clientOrderId, "reason" to e.reason),
        )

    fun fromOrderModified(e: BrokerEvent.OrderModified): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.modified",
            mapOf("orderId" to e.clientOrderId, "changes" to emptyMap<String, String>()),
        )

    fun fromTrade(e: TradeEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            null,
            "trade",
            mapOf(
                "orderId" to e.trade.orderId,
                "symbol" to e.trade.symbol,
                "side" to e.trade.side.name,
                "price" to e.trade.price,
                "qty" to e.trade.quantity,
                "ts" to e.trade.timestamp,
            ),
        )

    /**
     * A realized close from the engine's fill accounting (not a bus event): the
     * per-trade net P&L the exact analytics in qkt-insights are built on.
     * Deterministic id, so a re-sent batch dedupes at the collector.
     */
    fun tradeClosed(
        trade: com.qkt.execution.Trade,
        realized: BigDecimal,
        strategyId: String,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "tc-${trade.orderId}-${trade.timestamp}",
            seq = 0,
            ts = trade.timestamp,
            strategyId = strategyId.takeIf { it.isNotBlank() },
            type = "trade.closed",
            payload =
                mapOf(
                    "orderId" to trade.orderId,
                    "symbol" to trade.symbol,
                    "side" to trade.side.name,
                    "qty" to trade.quantity,
                    "price" to trade.price,
                    "realized" to realized,
                    "ts" to trade.timestamp,
                ),
        )

    fun fromRiskRejected(e: RiskRejectedEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.request.strategyId,
            "risk.rejected",
            mapOf(
                "reason" to e.reason,
                "symbol" to e.request.symbol,
                "side" to e.request.side.name,
                "qty" to e.request.quantity,
            ),
        )

    fun fromRiskHalted(e: RiskEvent.Halted): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "risk.halted",
            mapOf("strategyId" to e.strategyId.orEmpty(), "reason" to e.reason),
        )

    fun fromRiskResumed(e: RiskEvent.Resumed): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "risk.resumed",
            mapOf("strategyId" to e.strategyId.orEmpty()),
        )

    fun fromPositionReconciled(e: BrokerEvent.PositionReconciled): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            null,
            "position.reconciled",
            mapOf(
                "symbol" to e.symbol,
                "before" to (e.oldQty ?: BigDecimal.ZERO),
                "after" to e.newQty,
            ),
        )

    fun fromBalancesUpdated(e: BrokerEvent.BalancesUpdated): InsightsEnvelope =
        envelope(e.sequenceId, e.timestamp, null, "balances.updated", mapOf("balances" to e.balances))

    fun fromGatewayUnreachable(e: BrokerEvent.GatewayUnreachable): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            null,
            "gateway.unreachable",
            mapOf("detail" to "${e.broker} unreachable after ${e.consecutiveFailures} consecutive failures"),
        )

    fun equitySnapshot(
        ts: Long,
        strategyId: String,
        realized: BigDecimal,
        unrealized: BigDecimal,
        equity: BigDecimal,
        startingBalance: BigDecimal,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "eq-$strategyId-$ts",
            seq = 0,
            ts = ts,
            strategyId = strategyId,
            type = "snapshot.equity",
            payload =
                mapOf(
                    "strategyId" to strategyId,
                    "realized" to realized,
                    "unrealized" to unrealized,
                    "equity" to equity,
                    "startingBalance" to startingBalance,
                ),
        )

    fun positionSnapshot(
        ts: Long,
        strategyId: String,
        symbol: String,
        legs: List<PositionLeg>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "pos-$strategyId-$symbol-$ts",
            seq = 0,
            ts = ts,
            strategyId = strategyId,
            type = "snapshot.position",
            payload =
                mapOf(
                    "strategyId" to strategyId,
                    "symbol" to symbol,
                    "legs" to
                        legs.map {
                            mapOf(
                                "side" to it.side.name,
                                "qty" to it.quantity,
                                "entryPrice" to it.entryPrice,
                                "entryTs" to it.openedAt,
                            )
                        },
                ),
        )

    private fun envelope(
        seq: Long,
        ts: Long,
        strategyId: String?,
        type: String,
        payload: Map<String, Any?>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "e$seq",
            seq = seq,
            ts = ts,
            strategyId = strategyId?.takeIf { it.isNotBlank() },
            type = type,
            payload = payload,
        )
}
