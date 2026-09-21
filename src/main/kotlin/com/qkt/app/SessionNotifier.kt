package com.qkt.app

import com.qkt.common.Clock
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.observe.OrderJournal
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

/**
 * The alerts a live session raises itself rather than from a bus event: started, stopped, the
 * feed ending unexpectedly, and any critical error. A notifier that throws is never allowed to
 * break the session — the failure is logged and journalled instead, e.g. a Telegram timeout
 * while announcing a start leaves a `notification_failed` line with `handler=StrategyStarted`.
 */
internal class SessionNotifier(
    private val notifier: Notifier,
    private val notifyEvents: Set<NotifyEventKind>,
    private val journal: OrderJournal?,
    private val strategies: List<Pair<String, Strategy>>,
    private val clock: Clock,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /** Whether the session opted into alerts of [kind]. */
    fun enabled(kind: NotifyEventKind): Boolean = kind in notifyEvents

    /** Log and journal a notifier fault raised while handling [handler]. */
    fun recordFailure(
        strategyId: String?,
        handler: String,
        t: Throwable,
    ) {
        log.warn("[notify] handler failed for {}", handler, t)
        journal?.append(
            strategyId.orEmpty(),
            "notification_failed",
            mapOf(
                "handler" to handler,
                "reason" to (t.message ?: t::class.java.simpleName),
            ),
        )
    }

    /** Send a critical error alert for [strategyId]; a notifier fault is recorded under [handler]. */
    fun strategyError(
        strategyId: String,
        handler: String,
        message: () -> String,
    ) {
        runCatching {
            notifier.notify(
                NotificationEvent.StrategyError(
                    strategyId = strategyId,
                    message = message(),
                    timestamp = clock.now(),
                ),
            )
        }.onFailure { t -> recordFailure(strategyId, handler, t) }
    }

    /** Announce every hosted strategy as started, when the session opted in. */
    fun strategiesStarted() {
        if (NotifyEventKind.STRATEGY_STARTED in notifyEvents) {
            for ((strategyId, _) in strategies) {
                runCatching {
                    notifier.notify(
                        NotificationEvent.StrategyStarted(
                            strategyId = strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                }.onFailure { t -> recordFailure(strategyId, "StrategyStarted", t) }
            }
        }
    }

    /** Announce every hosted strategy as stopped, when the session opted in. */
    fun strategiesStopped() {
        if (NotifyEventKind.STRATEGY_STOPPED in notifyEvents) {
            for ((strategyId, _) in strategies) {
                runCatching {
                    notifier.notify(
                        NotificationEvent.StrategyStopped(
                            strategyId = strategyId,
                            flatten = false,
                            timestamp = clock.now(),
                        ),
                    )
                }.onFailure { t -> recordFailure(strategyId, "StrategyStopped", t) }
            }
        }
    }

    /** The feed ended while the session still expected ticks: a stopped alert, else an error alert. */
    fun unexpectedFeedEnd(reason: String) {
        for ((strategyId, _) in strategies) {
            val notification =
                when {
                    NotifyEventKind.STRATEGY_STOPPED in notifyEvents ->
                        NotificationEvent.StrategyStopped(
                            strategyId = strategyId,
                            flatten = false,
                            timestamp = clock.now(),
                            unexpected = true,
                            reason = reason,
                        )
                    NotifyEventKind.STRATEGY_ERROR in notifyEvents ->
                        NotificationEvent.StrategyError(
                            strategyId = strategyId,
                            message = reason,
                            timestamp = clock.now(),
                        )
                    else -> null
                }
            if (notification != null) {
                runCatching { notifier.notify(notification) }
                    .onFailure { t -> recordFailure(strategyId, "UnexpectedFeedEnd", t) }
            }
        }
    }
}
