package com.qkt.observe.insights

import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TradeEvent
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

    /**
     * Live venue account snapshot ("state.account"). Last-value semantics: the collector
     * keeps only the newest per (instance, broker), so the id just needs to be unique
     * per poll. Null fields (a venue that reports no margin) are omitted from the JSON
     * by [InsightsEnvelope.toJson]'s map writer — the contract wants absent, not null.
     */
    fun stateAccount(
        ts: Long,
        s: BrokerAccountState,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "acct-${s.broker}-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "state.account",
            payload =
                mapOf(
                    "broker" to s.broker,
                    "currency" to s.currency,
                    "balance" to s.balance,
                    "equity" to s.equity,
                    "margin" to s.margin,
                    "marginFree" to s.marginFree,
                    "openProfit" to s.openProfit,
                    "marginLevel" to s.marginLevel,
                    "login" to s.login.takeIf { it != 0L }?.toString(),
                    "server" to s.server.takeIf { it.isNotEmpty() },
                    "name" to s.name.takeIf { it.isNotEmpty() },
                ),
        )

    /**
     * Open venue positions snapshot ("state.positions"), full-replace semantics: the
     * collector swaps its whole list for this broker, so a position closed since the
     * last poll simply stops appearing. [StatePosition.strategyId] null marks a ticket
     * this daemon cannot attribute (an orphan) — shown as such, never hidden.
     */
    fun statePositions(
        ts: Long,
        broker: String,
        positions: List<StatePosition>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "posn-$broker-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "state.positions",
            payload =
                mapOf(
                    "broker" to broker,
                    "positions" to
                        positions.map { p ->
                            mapOf(
                                "ticket" to p.ticket,
                                "symbol" to p.symbol,
                                "side" to p.side,
                                "qty" to p.qty,
                                "entryPrice" to p.entryPrice,
                                "currentPrice" to p.currentPrice,
                                "profit" to p.profit,
                                "swap" to p.swap,
                                "openedAt" to p.openedAt,
                                "strategyId" to p.strategyId,
                            )
                        },
                ),
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
                    "magic" to d.magic,
                    "comment" to d.comment,
                    "ts" to d.ts,
                    "strategyId" to strategyId,
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

/**
 * One open venue position as the "state.positions" payload carries it — a
 * [com.qkt.broker.BrokerPositionTicket] plus the strategy id the poller attributed
 * (null when the ticket is an orphan this daemon cannot claim).
 */
data class StatePosition(
    val ticket: String,
    val symbol: String,
    /** "BUY" or "SELL". */
    val side: String,
    val qty: BigDecimal,
    val entryPrice: BigDecimal,
    val currentPrice: BigDecimal?,
    val profit: BigDecimal?,
    val swap: BigDecimal?,
    val openedAt: Long?,
    val strategyId: String?,
)
