package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.notify.EventTranslator
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

/**
 * Subscribes the operator-alert handlers for the bus-driven event kinds a session opted into,
 * e.g. with `HALTED` enabled a [RiskEvent.Halted] on the bus becomes one Telegram halt alert.
 *
 * Must be wired after the bus is constructed and before any publish — handlers registered after a
 * publish miss that event silently.
 *
 * Each handler is wrapped in [runCatching] so a notifier fault never propagates back into
 * the bus dispatch loop, whose semantics prevent later handlers from running if any handler
 * throws.
 *
 * [BrokerEvent.OrderRejected] omits symbol/side/quantity; the order manager recovers them
 * via [OrderManager.orderDetailsFor]. Not wired here: [NotificationEvent.DaemonStarted]
 * is a daemon-level concern fired by [com.qkt.cli.DaemonCommand];
 * [NotificationEvent.StrategyError] has no bus source yet.
 */
internal class NotifierSubscriptions(
    private val notifier: Notifier,
    private val notifyEvents: Set<NotifyEventKind>,
    private val strategies: List<Pair<String, Strategy>>,
    private val recordNotificationFailure: (strategyId: String?, handler: String, t: Throwable) -> Unit,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /** Register the enabled handlers on [bus], in the session's fixed order. */
    fun wire(
        bus: EventBus,
        orderManager: OrderManager,
    ) {
        if (NotifyEventKind.HALTED in notifyEvents) {
            bus.subscribe<RiskEvent.Halted> { ev ->
                runCatching { notifier.notify(EventTranslator.fromRiskHalted(ev)) }
                    .onFailure { t -> recordNotificationFailure(ev.strategyId, "Halted", t) }
            }
        }
        if (NotifyEventKind.RESUMED in notifyEvents) {
            bus.subscribe<RiskEvent.Resumed> { ev ->
                runCatching { notifier.notify(EventTranslator.fromRiskResumed(ev)) }
                    .onFailure { t -> recordNotificationFailure(ev.strategyId, "Resumed", t) }
            }
        }
        if (NotifyEventKind.POSITION_RECONCILED in notifyEvents) {
            // Best-effort strategyId: this session typically hosts one strategy. If multiple
            // are present, use the first; the alert still names the symbol so the operator
            // can disambiguate from logs.
            val ownerStrategyId = strategies.firstOrNull()?.first.orEmpty()
            bus.subscribe<BrokerEvent.PositionReconciled> { ev ->
                runCatching {
                    notifier.notify(
                        EventTranslator.fromPositionReconciled(event = ev, strategyId = ownerStrategyId),
                    )
                }.onFailure { t -> recordNotificationFailure(ownerStrategyId, "PositionReconciled", t) }
            }
        }
        if (NotifyEventKind.STRATEGY_ERROR in notifyEvents) {
            val ownerForError = strategies.firstOrNull()?.first.orEmpty()
            bus.subscribe<BrokerEvent.GatewayUnreachable> { ev ->
                runCatching {
                    notifier.notify(
                        NotificationEvent.StrategyError(
                            strategyId = ownerForError,
                            message =
                                "MT5 gateway '${ev.broker}' unreachable for ${ev.consecutiveFailures} " +
                                    "consecutive polls — position/pending reconciliation suspended",
                            timestamp = ev.timestamp,
                        ),
                    )
                }.onFailure { t -> recordNotificationFailure(ownerForError, "GatewayUnreachable", t) }
            }
            bus.subscribe<BrokerEvent.AccountEquityStale> { ev ->
                runCatching {
                    val age = ev.staleForMs?.let { "last good sample is ${it}ms old" } ?: "no successful sample"
                    notifier.notify(
                        NotificationEvent.StrategyError(
                            strategyId = ownerForError,
                            message =
                                "Broker equity '${ev.broker}' unavailable for ${ev.consecutiveFailures} " +
                                    "consecutive polls ($age) — drawdown basis is stale",
                            timestamp = ev.timestamp,
                        ),
                    )
                }.onFailure { t -> recordNotificationFailure(ownerForError, "AccountEquityStale", t) }
            }
            bus.subscribe<BrokerEvent.PositionProtectionChanged> { ev ->
                runCatching {
                    notifier.notify(
                        NotificationEvent.StrategyError(
                            strategyId = ev.strategyId.ifBlank { ownerForError },
                            message =
                                "CRITICAL venue protection changed: ${ev.broker} ${ev.symbol} ticket=${ev.ticket} " +
                                    "SL ${ev.oldStopLoss}->${ev.newStopLoss}, " +
                                    "TP ${ev.oldTakeProfit}->${ev.newTakeProfit}",
                            timestamp = ev.timestamp,
                        ),
                    )
                }.onFailure { t -> recordNotificationFailure(ownerForError, "PositionProtectionChanged", t) }
            }
        }
        if (NotifyEventKind.ORDER_REJECTED in notifyEvents) {
            bus.subscribe<BrokerEvent.OrderRejected> { ev ->
                runCatching {
                    val details = orderManager.orderDetailsFor(ev.clientOrderId)
                    if (details != null) {
                        notifier.notify(
                            EventTranslator.fromBrokerRejected(
                                event = ev,
                                symbol = details.symbol,
                                side = details.side,
                                quantity = details.quantity,
                            ),
                        )
                    } else {
                        log.warn("[notify] OrderRejected for unknown order {} — skipping alert", ev.clientOrderId)
                    }
                }.onFailure { t -> recordNotificationFailure(ev.strategyId, "OrderRejected", t) }
            }
        }
    }
}
