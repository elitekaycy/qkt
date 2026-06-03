package com.qkt.notify

/**
 * Maps a [NotificationEvent] to its [NotifyEventKind], or null for events that are not
 * event-gated (the daily summary, which is scheduled, not subscribed).
 */
fun kindOf(event: NotificationEvent): NotifyEventKind? =
    when (event) {
        is NotificationEvent.OrderRejected -> NotifyEventKind.ORDER_REJECTED
        is NotificationEvent.Halted -> NotifyEventKind.HALTED
        is NotificationEvent.Resumed -> NotifyEventKind.RESUMED
        is NotificationEvent.PositionReconciled -> NotifyEventKind.POSITION_RECONCILED
        is NotificationEvent.StrategyStarted -> NotifyEventKind.STRATEGY_STARTED
        is NotificationEvent.StrategyStopped -> NotifyEventKind.STRATEGY_STOPPED
        is NotificationEvent.StrategyError -> NotifyEventKind.STRATEGY_ERROR
        is NotificationEvent.DaemonStarted -> NotifyEventKind.DAEMON_STARTED
        is NotificationEvent.DailySummary -> null
    }

/**
 * Forwards events to [delegate] only when the event's kind is in [events], or when the event
 * has no kind (i.e. [NotificationEvent.DailySummary], which is always forwarded).
 *
 * e.g. events={HALTED} -> Halted reaches delegate; Resumed does not; DailySummary always does.
 */
class FilteringNotifier(
    private val events: Set<NotifyEventKind>,
    private val delegate: Notifier,
) : Notifier {
    override fun notify(event: NotificationEvent) {
        val kind = kindOf(event)
        if (kind == null || kind in events) delegate.notify(event)
    }

    override val metrics: NotifierMetrics? get() = delegate.metrics

    override fun close() = delegate.close()
}
