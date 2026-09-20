package com.qkt.observe.insights

import com.qkt.broker.BrokerAccountState

/**
 * Insights translation for polled venue state snapshots: the account, open positions and
 * resting orders. Emitted on the state poller's cadence, never per engine event. Mixed into
 * [InsightsTranslate].
 */
interface VenueStateInsights {
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
                                "stopLoss" to p.stopLoss,
                                "takeProfit" to p.takeProfit,
                                "requestedStopLoss" to p.requestedStopLoss,
                                "requestedTakeProfit" to p.requestedTakeProfit,
                                "magic" to p.magic,
                                "clientOrderId" to p.clientOrderId,
                            )
                        },
                ),
        )

    /**
     * Resting venue orders snapshot ("state.orders"), full-replace semantics like
     * "state.positions": a pending order that filled, expired, or was cancelled since
     * the last poll simply stops appearing.
     */
    fun stateOrders(
        ts: Long,
        broker: String,
        orders: List<StatePendingOrder>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "pord-$broker-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "state.orders",
            payload =
                mapOf(
                    "broker" to broker,
                    "orders" to
                        orders.map { o ->
                            mapOf(
                                "ticket" to o.ticket,
                                "symbol" to o.symbol,
                                "side" to o.side,
                                "orderType" to o.orderType,
                                "qty" to o.qty,
                                "price" to o.price,
                                "stopLoss" to o.stopLoss,
                                "takeProfit" to o.takeProfit,
                                "expiresAt" to o.expiresAt,
                                "createdAt" to o.createdAt,
                                "magic" to o.magic,
                                "clientOrderId" to o.clientOrderId,
                                "strategyId" to o.strategyId,
                            )
                        },
                ),
        )
}
