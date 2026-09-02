package com.qkt.observe.insights

import com.qkt.accounting.ConvertedMoney
import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.broker.OrderModification
import com.qkt.events.BrokerEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderRequestEvidence
import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.strategy.Signal
import com.qkt.strategy.targetSymbol
import java.math.BigDecimal

/**
 * Translates qkt bus events into [InsightsEnvelope]s matching the collector's contract.
 * Pure functions, no I/O — cheap enough for the engine thread. Returns null only for
 * source events that have no useful insights representation.
 *
 * Envelope ids combine the event identity fields so a re-sent batch dedupes at the
 * collector without colliding with another strategy session's bus sequence.
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
                            "orderSchemaVersion" to OrderRequestEvidence.SCHEMA_VERSION,
                            "order" to OrderRequestEvidence.payload(s.request),
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
                is Signal.Suppressed ->
                    "signal.suppressed" to
                        mapOf(
                            "intent" to "SUPPRESSED",
                            "symbol" to s.symbol,
                            "reason" to s.reason,
                        )
            }
        return envelope(e.sequenceId, e.timestamp, strategyId, type, payload)
    }

    /**
     * Translate an order submission. [referencePrice] is the sided execution price the
     * engine saw when it submitted — ask for BUY, bid for SELL — so fills can be measured
     * against it; null when no quote was available.
     */
    fun fromOrderSubmit(
        e: OrderEvent,
        referencePrice: java.math.BigDecimal? = null,
    ): InsightsEnvelope {
        val payload = OrderRequestEvidence.payload(e.request).toMutableMap()
        payload["orderSchemaVersion"] = OrderRequestEvidence.SCHEMA_VERSION
        referencePrice?.let { payload["referencePrice"] = it }
        if (e.request is OrderRequest.Bracket) {
            payload["planOrderId"] = e.request.id
            payload["orderId"] = e.request.entry.id
        }
        return envelope(
            e.sequenceId,
            e.timestamp,
            e.request.strategyId,
            "order.submit",
            payload,
        )
    }

    /** Translate one evaluated DSL rule edge with its exact closed-bar input. */
    fun fromRuleDecision(e: RuleDecisionEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "decision.rule_evaluated",
            mapOf(
                "decisionId" to e.decisionId,
                "ruleId" to e.ruleId,
                "strategyFingerprint" to e.strategyFingerprint,
                "ruleFingerprint" to e.ruleFingerprint,
                "conditionFingerprint" to e.conditionFingerprint,
                "conditionResult" to e.conditionResult,
                "alias" to e.alias,
                "broker" to e.broker,
                "timeframe" to e.timeframe,
                "signalCount" to e.signalCount,
                "candle" to candlePayload(e.candle),
            ),
        )

    /** Translate a DSL decision-to-order correlation into collector evidence. */
    fun fromDecisionOrderLinked(e: DecisionOrderLinkedEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "decision.order_linked",
            mapOf(
                "decisionId" to e.decisionId,
                "ruleId" to e.ruleId,
                "signalIndex" to e.signalIndex,
                "orderId" to e.orderId,
            ),
        )

    /** Translate one post-accounting fill slice, including position and PnL evidence. */
    fun fromFillAccounted(e: FillAccountedEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "fill.accounted",
            mapOf(
                "orderId" to e.orderId,
                "symbol" to e.symbol,
                "fillSliceId" to e.fillSliceId,
                "sourceFillSequenceId" to e.sourceFillSequenceId,
                "cumulativeFilled" to e.cumulativeFilled,
                "modeledCommissionAccount" to e.modeledCommissionAccount,
                "venueCostsAccount" to e.venueCostsAccount,
                "totalCostsAccount" to e.totalCostsAccount,
                "accountNativeRealized" to e.accountNativeRealized,
                "strategyNativeRealized" to e.strategyNativeRealized,
                "nativeCurrency" to e.nativeCurrency,
                "grossAccountRealized" to e.grossAccountRealized,
                "grossStrategyAccountRealized" to e.grossStrategyAccountRealized,
                "accountCurrency" to e.accountCurrency,
                "netAccountRealized" to e.netAccountRealized,
                "netStrategyAccountRealized" to e.netStrategyAccountRealized,
                "kind" to e.kind.name,
                "executedAt" to e.executedAt,
                "legId" to e.legId,
                "legAction" to e.legAction?.name,
                "conversionRate" to e.conversionRate,
                "conversionTimestampMs" to e.conversionTimestampMs,
                "conversionSource" to e.conversionSource,
                "contractSize" to e.contractSize,
                "accountPositionBefore" to positionPayload(e.accountPositionBefore),
                "accountPositionAfter" to positionPayload(e.accountPositionAfter),
                "strategyPositionBefore" to positionPayload(e.strategyPositionBefore),
                "strategyPositionAfter" to positionPayload(e.strategyPositionAfter),
                "reducedExposure" to e.reducedExposure,
                "partial" to e.partial,
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
                "typedVenueCosts" to venueCostsPayload(e.typedVenueCosts),
            ) + (e.exitReason?.let { mapOf("exitReason" to it.name) } ?: emptyMap()),
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
            ) + (e.exitReason?.let { mapOf("exitReason" to it.name) } ?: emptyMap()),
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
            e.strategyId,
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
     * A realized close from the engine's fill accounting (not a bus event).
     *
     * [netAccountRealized] is the canonical closed-trade PnL for dashboards: account-currency PnL
     * after modeled commissions and venue-reported costs. The legacy `realized` payload field is
     * retained as an alias of `netAccountRealized`. When [convertedRealized] is supplied, the
     * payload also carries gross account PnL before those costs plus native-currency and FX
     * conversion evidence, so consumers can reconcile net-vs-gross instead of guessing.
     *
     * Deterministic id, so a re-sent batch dedupes at the collector.
     */
    fun tradeClosed(
        trade: com.qkt.execution.Trade,
        netAccountRealized: BigDecimal,
        strategyId: String,
        convertedRealized: ConvertedMoney? = null,
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
                    "realized" to netAccountRealized,
                    "netAccountRealized" to netAccountRealized,
                    "grossAccountRealized" to convertedRealized?.account?.amount,
                    "accountRealized" to convertedRealized?.account?.amount,
                    "nativeRealized" to convertedRealized?.native?.amount,
                    "nativeCurrency" to convertedRealized?.native?.normalizedCurrency,
                    "accountCurrency" to convertedRealized?.account?.normalizedCurrency,
                    "currency" to convertedRealized?.account?.normalizedCurrency,
                    "fxRate" to convertedRealized?.conversion?.rate,
                    "fxRateTimestamp" to convertedRealized?.conversion?.timestamp,
                    "fxSource" to convertedRealized?.conversion?.source,
                    "costsAccount" to convertedRealized?.account?.amount?.subtract(netAccountRealized),
                    "pnlBasis" to "net_account_after_costs",
                    "realizedAliasOf" to "netAccountRealized",
                    "sideAttribution" to "fill_side",
                    "ts" to trade.timestamp,
                ),
        )

    fun fromSignalSuppressed(e: SignalSuppressedEvent): InsightsEnvelope =
        envelope(
            e.sequenceId,
            e.timestamp,
            e.strategyId,
            "signal.suppressed",
            mapOf(
                "reason" to e.reason,
                "symbol" to e.signal.targetSymbol(),
                "kind" to e.signal::class.simpleName,
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
                "orderSchemaVersion" to OrderRequestEvidence.SCHEMA_VERSION,
                "order" to OrderRequestEvidence.payload(e.request),
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

    /** Per-symbol quote-health transition detected while the source itself remains connected. */
    fun marketDataStale(
        source: String,
        symbol: String,
        ts: Long,
        reason: String,
    ): InsightsEnvelope = marketDataLifecycle("stale", "marketdata.stale", source, listOf(symbol), ts, reason)

    /**
     * The instance's currently-deployed strategy roster, emitted once per poll cycle.
     * Lets the collector tell live members from strategy ids that only linger from a
     * prior bench topology (e.g. after a reshard), instead of showing every id ever seen.
     * e.g. a 22-member bench emits {"strategies": ["forward_bench:s0", ...]} — 22 entries.
     */
    fun instanceRoster(
        ts: Long,
        strategyIds: Collection<String>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "roster-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "instance.roster",
            payload = mapOf("strategies" to strategyIds.toList()),
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

    /** Durable-state health emitted when persistence becomes unsafe. */
    fun statePersistence(
        ts: Long,
        strategyId: String?,
        health: com.qkt.persistence.PersistenceHealth,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "persistence-${strategyId ?: "session"}-$ts",
            seq = 0,
            ts = ts,
            strategyId = strategyId,
            type = "state.persistence",
            payload =
                mapOf(
                    "enabled" to health.enabled,
                    "totalWrites" to health.totalWrites,
                    "slowWrites" to health.slowWrites,
                    "failedWrites" to health.failedWrites,
                    "consecutiveFailures" to health.consecutiveFailures,
                    "failureEpisodes" to health.failureEpisodes,
                    "queueSize" to health.queueSize,
                    "callerRunsTotal" to health.callerRunsTotal,
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
                    "fee" to d.fee,
                    "clientOrderId" to d.clientOrderId,
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

    /** Announces a deployed portfolio book so Insights can retain book-level metadata. */
    fun portfolioConfigured(
        portfolioId: String,
        ts: Long,
        capital: BigDecimal?,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "portfolio-configured-$portfolioId-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "portfolio.configured",
            payload = mapOf("portfolioId" to portfolioId, "capital" to capital, "ts" to ts),
        )

    /** Records the current child allocation weights for a portfolio book. */
    fun portfolioAllocationUpdated(
        portfolioId: String,
        ts: Long,
        allocations: Map<String, BigDecimal>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "portfolio-allocation-$portfolioId-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "portfolio.allocation.updated",
            payload = mapOf("portfolioId" to portfolioId, "allocations" to allocations, "ts" to ts),
        )

    /**
     * Per-strategy equity sample ("snapshot.equity"): the store's `equity_snapshots` rows and
     * `strategies.equity/starting_balance` are fed ONLY by this type, so its absence blanks
     * every equity/drawdown panel even while venue `state.*` streams fine (#1073). Emitted on
     * the STATE poller cadence from the session's [com.qkt.pnl.StrategyPnL] view.
     */
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

    /** Records an aggregated realized/unrealized equity sample for a portfolio book. */
    fun portfolioEquityUpdated(
        portfolioId: String,
        ts: Long,
        equity: BigDecimal,
        realized: BigDecimal,
        unrealized: BigDecimal,
        perStrategy: Map<String, BigDecimal>,
    ): InsightsEnvelope =
        InsightsEnvelope(
            id = "portfolio-equity-$portfolioId-$ts",
            seq = 0,
            ts = ts,
            strategyId = null,
            type = "portfolio.equity.updated",
            payload =
                mapOf(
                    "portfolioId" to portfolioId,
                    "equity" to equity,
                    "realized" to realized,
                    "unrealized" to unrealized,
                    "perStrategy" to perStrategy,
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
            id = "event-$type-${strategyId.orEmpty()}-$ts-$seq",
            seq = seq,
            ts = ts,
            strategyId = strategyId?.takeIf { it.isNotBlank() },
            type = type,
            payload = payload,
        )

    private fun orderModificationPayload(changes: OrderModification): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "newQuantity" to changes.newQuantity,
            "newLimitPrice" to changes.newLimitPrice,
            "newStopPrice" to changes.newStopPrice,
        )

    private fun positionPayload(position: Position?): Map<String, Any?>? =
        position?.let {
            mapOf(
                "symbol" to it.symbol,
                "quantity" to it.quantity,
                "avgEntryPrice" to it.avgEntryPrice,
                "openedAtMs" to it.openedAt,
            )
        }

    private fun candlePayload(candle: Candle): Map<String, Any?> =
        mapOf(
            "symbol" to candle.symbol,
            "startTimeMs" to candle.startTime,
            "endTimeMs" to candle.endTime,
            "open" to candle.open,
            "high" to candle.high,
            "low" to candle.low,
            "close" to candle.close,
            "volume" to candle.volume,
            "bid" to candle.bid,
            "ask" to candle.ask,
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
    /** Venue-side protective levels; null when unsupported, zero when absent on MT5. */
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    /** What qkt last requested — differs from venue truth while a modify is in flight. */
    val requestedStopLoss: BigDecimal? = null,
    val requestedTakeProfit: BigDecimal? = null,
    val magic: Int? = null,
    val clientOrderId: String? = null,
)

/** One resting venue order as the "state.orders" payload carries it. */
data class StatePendingOrder(
    val ticket: String,
    val symbol: String,
    /** "BUY" or "SELL". */
    val side: String,
    /** Venue order type string, e.g. "ORDER_TYPE_BUY_LIMIT". */
    val orderType: String,
    val qty: BigDecimal,
    val price: BigDecimal?,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val expiresAt: Long? = null,
    val createdAt: Long? = null,
    val magic: Int? = null,
    val clientOrderId: String? = null,
    val strategyId: String? = null,
)
