package com.qkt.observe.insights

import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.broker.OrderModification
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StringLit
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Translates qkt bus events into [InsightsEnvelope]s matching the collector's contract.
 * Pure functions, no I/O — cheap enough for the engine thread. Returns null only for
 * source events that have no useful insights representation.
 *
 * Envelope ids derive from the bus sequenceId ("e42"), which is unique per instance,
 * so a re-sent batch dedupes at the collector instead of double-counting.
 */
object InsightsTranslate {
    fun fromSignal(e: SignalEvent): InsightsEnvelope? {
        val strategyId = e.strategyId.takeIf { it.isNotBlank() }
        val (type, payload) =
            when (val s = e.signal) {
                is Signal.Buy ->
                    "signal" to
                        mapOf(
                            "intent" to "BUY",
                            "symbol" to s.symbol,
                            "side" to "BUY",
                            "qty" to s.size,
                        )
                is Signal.Sell ->
                    "signal" to
                        mapOf(
                            "intent" to "SELL",
                            "symbol" to s.symbol,
                            "side" to "SELL",
                            "qty" to s.size,
                        )
                is Signal.Submit ->
                    "signal" to
                        mapOf(
                            "intent" to "SUBMIT",
                            "symbol" to s.request.symbol,
                            "side" to s.request.side.name,
                            "qty" to s.request.quantity,
                            "order" to orderPayload(s.request),
                        )
                is Signal.CancelPendingForSymbol ->
                    "signal.cancel" to
                        mapOf(
                            "intent" to "CANCEL_PENDING_FOR_SYMBOL",
                            "symbol" to s.symbol,
                        )
                is Signal.ArmLatch ->
                    "signal.latch_armed" to
                        mapOf(
                            "intent" to "ARM_LATCH",
                            "reference" to s.compiled.reference.toString(),
                            "offset" to s.compiled.offset.toString(),
                            "streamAlias" to s.compiled.streamAlias,
                            "name" to s.compiled.name,
                            "armWindowMs" to s.compiled.armWindowMs,
                            "expiresAt" to e.timestamp + s.compiled.armWindowMs,
                        )
            }
        return envelope(e.sequenceId, e.timestamp, strategyId, type, payload)
    }

    fun fromOrderSubmit(e: OrderEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.request.strategyId,
            "order.submit",
            orderPayload(e.request),
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
                "typedVenueCosts" to venueCostsPayload(e.typedVenueCosts),
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
                "brokerOrderId" to e.brokerOrderId,
                "symbol" to e.symbol,
                "side" to e.side.name,
                "price" to e.price,
                "qty" to e.quantity,
                "cumulativeQty" to e.cumulativeFilled,
                "venueCosts" to e.venueCosts,
                "typedVenueCosts" to venueCostsPayload(e.typedVenueCosts),
            ),
        )

    fun fromOrderCancelled(e: BrokerEvent.OrderCancelled): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.cancelled",
            mapOf("orderId" to e.clientOrderId, "brokerOrderId" to e.brokerOrderId, "reason" to e.reason),
        )

    fun fromOrderRejected(e: BrokerEvent.OrderRejected): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.rejected",
            mapOf("orderId" to e.clientOrderId, "brokerOrderId" to e.brokerOrderId, "reason" to e.reason),
        )

    fun fromOrderModified(e: BrokerEvent.OrderModified): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "order.modified",
            mapOf(
                "orderId" to e.clientOrderId,
                "brokerOrderId" to e.brokerOrderId,
                "changes" to orderModificationPayload(e.changes),
            ),
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
            mapOf("strategyId" to e.strategyId, "reason" to e.reason),
        )

    fun fromRiskResumed(e: RiskEvent.Resumed): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "risk.resumed",
            mapOf("strategyId" to e.strategyId),
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
                "oldQty" to e.oldQty,
                "newQty" to e.newQty,
                "oldAvgPx" to e.oldAvgPx,
                "newAvgPx" to e.newAvgPx,
                "source" to e.source,
                "reason" to e.reason,
            ),
        )

    fun fromBalancesUpdated(e: BrokerEvent.BalancesUpdated): InsightsEnvelope =
        envelope(
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
        envelope(
            e.sequenceId,
            e.timestamp,
            null,
            "gateway.unreachable",
            mapOf("detail" to "${e.broker} unreachable after ${e.consecutiveFailures} consecutive failures"),
        )

    fun fromBrokerGatewayUnreachable(e: BrokerEvent.GatewayUnreachable): InsightsEnvelope =
        envelope(
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
        return envelope(
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

    fun marketDataConnected(
        source: String,
        symbols: List<String>,
        ts: Long,
        reason: String = "session-start",
    ): InsightsEnvelope = marketDataLifecycle("connected", "marketdata.connected", source, symbols, ts, reason)

    fun marketDataDisconnected(
        source: String,
        symbols: List<String>,
        ts: Long,
        reason: String,
    ): InsightsEnvelope = marketDataLifecycle("disconnected", "marketdata.disconnected", source, symbols, ts, reason)

    fun marketDataReconnected(
        source: String,
        symbols: List<String>,
        ts: Long,
        reason: String = "source-reconnected",
    ): InsightsEnvelope = marketDataLifecycle("reconnected", "marketdata.reconnected", source, symbols, ts, reason)

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

    fun strategyStarted(
        strategyId: String,
        ts: Long,
        metadata: Map<String, Any?> = emptyMap(),
    ): InsightsEnvelope =
        run {
            val payload =
                linkedMapOf<String, Any?>(
                    "strategyId" to strategyId,
                    "ts" to ts,
                )
            for ((k, v) in metadata) {
                if (k == "strategyId" || k == "ts") continue
                payload[k] = v
            }
            InsightsEnvelope(
                id = "strategy-started-$strategyId-$ts",
                seq = 0,
                ts = ts,
                strategyId = strategyId.takeIf { it.isNotBlank() },
                type = "strategy.started",
                payload = payload,
            )
        }

    fun strategyStopped(
        strategyId: String,
        ts: Long,
        flatten: Boolean,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "strategy-stopped-$strategyId-$ts",
            seq = 0,
            ts = ts,
            strategyId = strategyId.takeIf { it.isNotBlank() },
            type = "strategy.stopped",
            payload =
                mapOf(
                    "strategyId" to strategyId,
                    "flatten" to flatten,
                    "ts" to ts,
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

    private fun orderPayload(request: OrderRequest): Map<String, Any?> {
        val base =
            linkedMapOf<String, Any?>(
                "orderId" to request.id,
                "orderType" to request.javaClass.simpleName,
                "symbol" to request.symbol,
                "side" to request.side.name,
                "qty" to request.quantity,
                "strategyId" to request.strategyId.takeIf { it.isNotBlank() },
                "timeInForce" to request.timeInForce.name,
                "createdTs" to request.timestamp,
                "expiresAt" to request.expiresAt,
            )
        when (request) {
            is OrderRequest.Market -> {
                base["closesTicket"] = request.closesTicket
                base["closesLegId"] = request.closesLegId
                base["partialClose"] = request.partialClose
            }
            is OrderRequest.Limit -> base["limitPrice"] = request.limitPrice
            is OrderRequest.Stop -> base["stopPrice"] = request.stopPrice
            is OrderRequest.StopLimit -> {
                base["stopPrice"] = request.stopPrice
                base["limitPrice"] = request.limitPrice
            }
            is OrderRequest.IfTouched -> {
                base["triggerPrice"] = request.triggerPrice
                base["onTrigger"] = request.onTrigger.name
                base["limitPrice"] = request.limitPrice
            }
            is OrderRequest.TrailingStop -> {
                base["trailAmount"] = request.trailAmount
                base["trailMode"] = request.trailMode.name
            }
            is OrderRequest.ArmedTrailingStop -> {
                base["entryPrice"] = request.entryPrice
                base["trailDistance"] = request.trailDistance
                base["mfeThreshold"] = request.mfeThreshold
            }
            is OrderRequest.TrailingStopLimit -> {
                base["trailAmount"] = request.trailAmount
                base["trailMode"] = request.trailMode.name
                base["limitOffset"] = request.limitOffset
            }
            is OrderRequest.StandaloneOCO -> {
                base["leg1"] = orderPayload(request.leg1)
                base["leg2"] = orderPayload(request.leg2)
            }
            is OrderRequest.OTO -> {
                base["parent"] = orderPayload(request.parent)
                base["children"] = request.children.map(::orderPayload)
            }
            is OrderRequest.Bracket -> {
                base["entry"] = orderPayload(request.entry)
                base["takeProfit"] = request.takeProfit
                base["stopLoss"] = stopLossPayload(request.stopLoss)
                base["takeProfitAst"] = childPricePayload(request.takeProfitAst)
                base["stopLossAst"] = childPricePayload(request.stopLossAst)
            }
            is OrderRequest.ScaleOut -> {
                base["basis"] = orderPayload(request.basis)
                base["legs"] =
                    request.legs.map {
                        mapOf(
                            "priceTarget" to it.priceTarget,
                            "fraction" to it.fraction,
                        )
                    }
            }
            is OrderRequest.TimeExit -> {
                base["target"] = orderPayload(request.target)
                base["deadline"] = request.deadline.toEpochMilli()
                base["onExpiry"] = request.onExpiry.name
            }
            is OrderRequest.Stack -> {
                base["layers"] = request.plan.layers.size
                base["stackLayers"] = request.plan.layers.map(::stackLayerPayload)
                base["withinMillis"] = request.plan.withinMillis
                base["hasOuterBracket"] = request.plan.outerBracket != null
                base["outerBracket"] =
                    request.plan.outerBracket?.let {
                        mapOf(
                            "takeProfit" to childPricePayload(it.takeProfit),
                            "stopLoss" to childPricePayload(it.stopLoss),
                        )
                    }
            }
        }
        return base
    }

    private fun stackLayerPayload(layer: com.qkt.execution.LayerSpec): Map<String, Any?> =
        mapOf(
            "index" to layer.index,
            "sizing" to sizingPayload(layer.sizing),
            "orderType" to orderTypePayload(layer.orderType),
            "trigger" to layerTriggerPayload(layer.trigger),
            "resolvedQuantity" to layer.resolvedQuantity,
        )

    private fun layerTriggerPayload(trigger: com.qkt.execution.LayerTrigger): Map<String, Any?> =
        when (trigger) {
            is com.qkt.execution.Immediate ->
                mapOf("type" to "Immediate")
            is com.qkt.execution.At ->
                mapOf(
                    "type" to "At",
                    "price" to exprPayload(trigger.price),
                    "direction" to trigger.direction.name,
                )
        }

    private fun sizingPayload(sizing: SizingAst): Map<String, Any?> =
        when (sizing) {
            is SizeQty ->
                mapOf("type" to "SizeQty", "expr" to exprPayload(sizing.expr))
            is SizeNotional ->
                mapOf("type" to "SizeNotional", "usd" to exprPayload(sizing.usd))
            is SizePctEquity ->
                mapOf("type" to "SizePctEquity", "frac" to exprPayload(sizing.frac))
            is SizePctBalance ->
                mapOf("type" to "SizePctBalance", "frac" to exprPayload(sizing.frac))
            is SizeRiskFrac ->
                mapOf("type" to "SizeRiskFrac", "frac" to exprPayload(sizing.frac))
            is SizeRiskAbs ->
                mapOf("type" to "SizeRiskAbs", "usd" to exprPayload(sizing.usd))
            is SizePositionFull ->
                mapOf("type" to "SizePositionFull", "stream" to sizing.stream)
        }

    private fun orderTypePayload(orderType: OrderTypeAst): Map<String, Any?> =
        when (orderType) {
            is com.qkt.dsl.ast.Market ->
                mapOf("type" to "Market")
            is com.qkt.dsl.ast.Limit ->
                mapOf("type" to "Limit", "price" to exprPayload(orderType.price))
            is com.qkt.dsl.ast.Stop ->
                mapOf("type" to "Stop", "price" to exprPayload(orderType.price))
            is com.qkt.dsl.ast.StopLimit ->
                mapOf(
                    "type" to "StopLimit",
                    "stopPrice" to exprPayload(orderType.stopPrice),
                    "limitPrice" to exprPayload(orderType.limitPrice),
                )
            is com.qkt.dsl.ast.TrailingBy ->
                mapOf("type" to "TrailingBy", "distance" to exprPayload(orderType.distance))
            is com.qkt.dsl.ast.TrailingPct ->
                mapOf("type" to "TrailingPct", "frac" to exprPayload(orderType.frac))
        }

    private fun childPricePayload(child: ChildPriceAst?): Map<String, Any?>? =
        when (child) {
            null -> null
            is ChildAt ->
                mapOf("type" to "At", "price" to exprPayload(child.price))
            is ChildBy ->
                mapOf("type" to "By", "distance" to exprPayload(child.distance))
            is ChildPct ->
                mapOf("type" to "Pct", "percent" to exprPayload(child.percent))
            is ChildRr ->
                mapOf("type" to "Rr", "multiplier" to exprPayload(child.multiplier))
            is ChildArmedTrail ->
                mapOf(
                    "type" to "ArmedTrail",
                    "trailDistance" to exprPayload(child.trailDistance),
                    "mfeThreshold" to exprPayload(child.mfeThreshold),
                )
        }

    private fun exprPayload(expr: ExprAst): Map<String, Any?> =
        when (expr) {
            is NumLit -> mapOf("type" to "NumLit", "value" to expr.value)
            is BoolLit -> mapOf("type" to "BoolLit", "value" to expr.value)
            is StringLit -> mapOf("type" to "StringLit", "value" to expr.value)
            else ->
                mapOf(
                    "type" to (expr::class.simpleName ?: expr.javaClass.simpleName),
                    "text" to expr.toString(),
                )
        }

    private fun stopLossPayload(stopLoss: StopLossSpec): Map<String, Any?> =
        when (stopLoss) {
            is StopLossSpec.Fixed ->
                mapOf(
                    "type" to "Fixed",
                    "price" to stopLoss.price,
                )
            is StopLossSpec.ArmedTrail ->
                mapOf(
                    "type" to "ArmedTrail",
                    "trailDistance" to stopLoss.trailDistance,
                    "mfeThreshold" to stopLoss.mfeThreshold,
                )
        }

    private fun orderModificationPayload(changes: OrderModification): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "newQuantity" to changes.newQuantity,
            "newLimitPrice" to changes.newLimitPrice,
            "newStopPrice" to changes.newStopPrice,
        )

    private fun marketDataLifecycle(
        state: String,
        type: String,
        source: String,
        symbols: List<String>,
        ts: Long,
        reason: String,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "marketdata-$state-$source-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = type,
            payload =
                mapOf(
                    "source" to source,
                    "symbols" to symbols,
                    "state" to state,
                    "reason" to reason,
                    "ts" to ts,
                ),
        )

    private fun venueCostsPayload(costs: List<com.qkt.accounting.VenueCost>): List<Map<String, Any?>> =
        costs.map {
            mapOf(
                "kind" to it.kind.name,
                "amount" to it.amount.amount,
                "currency" to it.amount.normalizedCurrency,
                "ts" to it.timestamp,
            )
        }
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
