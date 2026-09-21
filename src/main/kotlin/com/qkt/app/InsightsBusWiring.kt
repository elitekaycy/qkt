package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.events.SignalEvent
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import com.qkt.observe.insights.InsightsTranslate

/**
 * Streams allow-listed event families to the insights sink, e.g. with only `ORDER` enabled an
 * accepted order reaches the collector while signals and halts do not. Each handler only builds
 * a small envelope and enqueues it — the sink's own thread does JSON and HTTP, so
 * none of this touches the engine loop's latency. Mirrors [OrderJournalWiring]'s shape.
 */
internal class InsightsBusWiring(
    private val insightsEvents: Set<InsightsEventFamily>,
) {
    /** Subscribe the enabled families on [bus]; call exactly where the session wires insights. */
    fun wire(
        bus: EventBus,
        sink: InsightsSink,
        prices: MarketPriceProvider,
    ) {
        val t = InsightsTranslate
        if (InsightsEventFamily.SIGNAL in insightsEvents) {
            bus.subscribe<SignalEvent> { e -> t.fromSignal(e)?.let(sink::offer) }
            bus.subscribe<com.qkt.events.RuleDecisionEvent> { e -> sink.offer(t.fromRuleDecision(e)) }
        }
        if (InsightsEventFamily.ORDER in insightsEvents) {
            bus.subscribe<com.qkt.events.OrderEvent> { e ->
                // The sided execution price the engine saw at submission: the slippage
                // baseline for market entries, which carry no price of their own.
                val reference = prices.executionPrice(e.request.symbol, e.request.side)
                sink.offer(t.fromOrderSubmit(e, reference))
            }
            bus.subscribe<com.qkt.events.DecisionOrderLinkedEvent> { e ->
                sink.offer(t.fromDecisionOrderLinked(e))
            }
            bus.subscribe<BrokerEvent.OrderAccepted> { e -> sink.offer(t.fromOrderAccepted(e)) }
            bus.subscribe<BrokerEvent.OrderFilled> { e -> sink.offer(t.fromOrderFilled(e)) }
            bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> sink.offer(t.fromOrderPartiallyFilled(e)) }
            bus.subscribe<BrokerEvent.OrderCancelled> { e -> sink.offer(t.fromOrderCancelled(e)) }
            bus.subscribe<BrokerEvent.OrderRejected> { e -> sink.offer(t.fromOrderRejected(e)) }
            bus.subscribe<BrokerEvent.OrderModified> { e -> sink.offer(t.fromOrderModified(e)) }
        }
        if (InsightsEventFamily.TRADE in insightsEvents) {
            bus.subscribe<com.qkt.events.TradeEvent> { e -> sink.offer(t.fromTrade(e)) }
            bus.subscribe<com.qkt.events.FillAccountedEvent> { e -> sink.offer(t.fromFillAccounted(e)) }
        }
        if (InsightsEventFamily.RISK in insightsEvents) {
            bus.subscribe<com.qkt.events.RiskRejectedEvent> { e -> sink.offer(t.fromRiskRejected(e)) }
            bus.subscribe<com.qkt.events.SignalSuppressedEvent> { e -> sink.offer(t.fromSignalSuppressed(e)) }
            bus.subscribe<RiskEvent.Halted> { e -> sink.offer(t.fromRiskHalted(e)) }
            bus.subscribe<RiskEvent.Resumed> { e -> sink.offer(t.fromRiskResumed(e)) }
        }
        if (InsightsEventFamily.POSITION in insightsEvents) {
            bus.subscribe<BrokerEvent.PositionReconciled> { e -> sink.offer(t.fromPositionReconciled(e)) }
            bus.subscribe<BrokerEvent.BalancesUpdated> { e -> sink.offer(t.fromBalancesUpdated(e)) }
            bus.subscribe<BrokerEvent.GatewayUnreachable> { e -> sink.offer(t.fromGatewayUnreachable(e)) }
        }
        if (InsightsEventFamily.LIFECYCLE in insightsEvents) {
            bus.subscribe<BrokerEvent.GatewayUnreachable> { e -> sink.offer(t.fromBrokerGatewayUnreachable(e)) }
            bus.subscribe<BrokerEvent.ConnectionChanged> { e -> sink.offer(t.fromBrokerConnectionChanged(e)) }
        }
    }
}
