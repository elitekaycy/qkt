package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.events.BrokerEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.observability.LatencyRegistry
import com.qkt.risk.RunawayBreaker
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Subscribes the pipeline's handlers for order outcomes: fills and partial fills are booked,
 * folded into the accumulators, handed to exit hooks and reported; rejections and cancels reach
 * the exit hooks, the runaway breaker and the owning DSL strategy (a rule's unfilled market exit re-arms
 * its rule, see [UnfilledExitAlerts]), and a venue rejection is reported
 * through [onRejected] like a risk rejection. [subscribe] registers them in
 * the one order the pipeline depends on, so it must run exactly where the pipeline calls it.
 */
internal class OrderOutcomeWiring(
    private val bus: EventBus,
    private val orderManager: OrderManager,
    private val exitHookManager: ExitHookManager,
    private val booker: ExecutionBooker,
    private val fold: AccountedFillFold,
    private val reporter: ExecutionReporter,
    private val runawayBreaker: RunawayBreaker?,
    private val onRejected: (RiskRejectedEvent) -> Unit,
    private val latency: LatencyRegistry,
    onProtectionFailure: (strategyId: String, message: String) -> Unit,
    strategies: List<Pair<String, Strategy>>,
) {
    // Logged under the pipeline's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)
    private val dslStrategiesById: Map<String, DslCompiledStrategy> =
        strategies
            .mapNotNull { (id, strategy) ->
                (strategy as? DslCompiledStrategy)?.let { id to it }
            }.toMap()
    private val repeats = RepeatedExecutions(orderManager::getOrder)
    private val unfilledExits = UnfilledExitAlerts(onProtectionFailure)

    /** Register every order-outcome handler on the bus, in dispatch order. */
    fun subscribe() {
        // subscribeFirst: the books must reflect this fill BEFORE any handler with venue
        // side effects runs — OrderManager cancels OCO siblings and dispatches children,
        // and the stack orchestrator risk-checks child tiers against position state.
        // Both subscribe earlier in construction order, so ordinary subscribe() here
        // would run them against a pre-fill book (#374, #377).
        // The fold: the ONLY writer of every realized accumulator. It subscribes first on the
        // accounted event so halts see the amount before any consumer with venue side effects.
        bus.subscribeFirst<FillAccountedEvent> { a -> fold.fold(a) }
        // After the fold, so the books already hold the fill. Only a fill that flips the strategy's
        // position between flat and held counts; adds and partial closes change nothing here.
        bus.subscribe<FillAccountedEvent> { a ->
            val wasHeld = !a.strategyPositionBefore.isFlat()
            val nowHeld = !a.strategyPositionAfter.isFlat()
            if (wasHeld != nowHeld) {
                val at = a.executedAt.takeIf { it > 0L } ?: a.timestamp
                dslStrategiesById[a.strategyId]?.onPositionStateChanged(a.symbol, nowHeld, at)
            }
        }
        bus.subscribeFirst<BrokerEvent.OrderFilled> { e ->
            if (e.strategyId.isBlank()) {
                // An execution slice with no owner cannot be booked: position books and PnL
                // will drift from the venue until the next restart reconciles them. Say so
                // loudly instead of silently skipping (#1061 book-drift symptom).
                log.warn(
                    "unattributed fill dropped: order_id={} broker_order_id={} {} {} qty={} — " +
                        "position books not adjusted",
                    e.clientOrderId,
                    e.brokerOrderId,
                    e.symbol,
                    e.side,
                    e.quantity,
                )
                return@subscribeFirst
            }
            if (repeats.isRepeatedFill(e)) {
                log.warn(
                    "repeated fill not booked: order_id={} broker_order_id={} qty={} — the order already filled",
                    e.clientOrderId,
                    e.brokerOrderId,
                    e.quantity,
                )
                return@subscribeFirst
            }
            if (latency.enabled) latency.observeFill(e.clientOrderId, e.strategyId)
            val accounted =
                booker.book(e, cumulativeFilled = cumulativeAfter(e.clientOrderId, e.quantity), partial = false)
                    ?: return@subscribeFirst
            bus.publish(accounted.event)
            exitHookManager.onFill(
                event = e,
                netRealizedPnl = accounted.event.netStrategyAccountRealized,
                strategyAfterQuantity = accounted.event.strategyPositionAfter?.quantity ?: BigDecimal.ZERO,
                reducedExposure = accounted.event.reducedExposure,
                deferDispatch = true,
            )
            reporter.report(e, accounted)
        }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> exitHookManager.dispatchReady(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> dslStrategiesById[e.strategyId]?.onOrderFilled(e.clientOrderId) }
        // First: judged against the order before the order manager applies the slice.
        bus.subscribeFirst<BrokerEvent.OrderPartiallyFilled> { e -> repeats.observeSlice(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e ->
            if (repeats.takeRepeatedSlice(e)) {
                log.warn(
                    "repeated execution slice not booked: order_id={} cumulative={} — the order already holds it",
                    e.clientOrderId,
                    e.cumulativeFilled,
                )
                return@subscribe
            }
            if (e.strategyId.isBlank()) return@subscribe
            val asFill =
                BrokerEvent.OrderFilled(
                    clientOrderId = e.clientOrderId,
                    brokerOrderId = e.brokerOrderId,
                    symbol = e.symbol,
                    side = e.side,
                    price = e.price,
                    quantity = e.quantity,
                    strategyId = e.strategyId,
                    timestamp = e.timestamp,
                    sequenceId = e.sequenceId,
                    venueCosts = e.venueCosts,
                    typedVenueCosts = e.typedVenueCosts,
                    exitReason = e.exitReason,
                )
            val accounted =
                booker.book(asFill, cumulativeFilled = e.cumulativeFilled, partial = true)
                    ?: return@subscribe
            bus.publish(accounted.event)
            exitHookManager.onFill(
                event = asFill,
                netRealizedPnl = accounted.event.netStrategyAccountRealized,
                strategyAfterQuantity = accounted.event.strategyPositionAfter?.quantity ?: BigDecimal.ZERO,
                reducedExposure = accounted.event.reducedExposure,
            )
            reporter.report(asFill, accounted)
            dslStrategiesById[e.strategyId]?.onOrderTerminal(e.clientOrderId)
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> runawayBreaker?.recordRejection(e.strategyId) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> exitHookManager.onRejected(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            log.warn("Order rejected: ${e.clientOrderId} reason=${e.reason}")
            ownerOf(e).let { id -> unfilledExits.onEnded(id, dslStrategiesById[id], e.clientOrderId, e.reason) }
            dslStrategiesById[e.strategyId]?.onOrderRejected(e.clientOrderId)
            // Reported beside risk rejections, so a run's rejections include what the venue refused.
            orderManager.getOrder(e.clientOrderId)?.let {
                onRejected(RiskRejectedEvent(it.request, "venue: ${e.reason}", e.timestamp))
            }
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e ->
            exitHookManager.onCancelled(e)
            ownerOf(e).let { id -> unfilledExits.onEnded(id, dslStrategiesById[id], e.clientOrderId, e.reason) }
            dslStrategiesById[e.strategyId]?.onOrderTerminal(e.clientOrderId)
        }
        bus.subscribe<RiskRejectedEvent> { e ->
            dslStrategiesById[e.request.strategyId]?.onOrderRejected(e.request.id)
            onRejected(e)
        }
    }

    // A venue event may leave the owner blank; the order book knows it.
    private fun ownerOf(e: BrokerEvent.OrderEvent): String =
        e.strategyId.ifBlank {
            orderManager
                .getOrder(e.clientOrderId)
                ?.request
                ?.strategyId
                .orEmpty()
        }

    /**
     * The order's executed quantity once [sliceQuantity] is included: the manager's running
     * total is read before it books this slice (this handler subscribes first), so add it.
     * A replayed terminal fill therefore reports a cumulative the leg already holds.
     */
    private fun cumulativeAfter(
        clientOrderId: String,
        sliceQuantity: BigDecimal,
    ): BigDecimal =
        (orderManager.getOrder(clientOrderId)?.cumulativeFilledQuantity ?: BigDecimal.ZERO).add(sliceQuantity)
}

private fun com.qkt.positions.Position?.isFlat(): Boolean = this == null || quantity.signum() == 0
