package com.qkt.notify

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private class CapturingNotifier(
    override var metrics: NotifierMetrics? = null,
) : Notifier {
    val received = mutableListOf<NotificationEvent>()
    var closed = false

    override fun notify(event: NotificationEvent) {
        received.add(event)
    }

    override fun close() {
        closed = true
    }
}

class FilteringNotifierTest {
    @Test
    fun `subscribed event reaches delegate`() {
        val delegate = CapturingNotifier()
        val notifier = FilteringNotifier(setOf(NotifyEventKind.HALTED), delegate)
        val event = NotificationEvent.Halted(strategyId = null, reason = "test", timestamp = 0L)

        notifier.notify(event)

        assertThat(delegate.received).containsExactly(event)
    }

    @Test
    fun `unsubscribed event does not reach delegate`() {
        val delegate = CapturingNotifier()
        val notifier = FilteringNotifier(setOf(NotifyEventKind.HALTED), delegate)
        val event = NotificationEvent.Resumed(strategyId = null, timestamp = 0L)

        notifier.notify(event)

        assertThat(delegate.received).isEmpty()
    }

    @Test
    fun `DailySummary always reaches delegate regardless of event set`() {
        val delegate = CapturingNotifier()
        val notifier = FilteringNotifier(emptySet(), delegate)
        val event = NotificationEvent.DailySummary(asOfUtc = "2026-06-03", strategies = emptyList(), timestamp = 0L)

        notifier.notify(event)

        assertThat(delegate.received).containsExactly(event)
    }

    @Test
    fun `metrics delegates to underlying notifier`() {
        val m =
            object : NotifierMetrics {
                override val sent = 7L
                override val dropped = 0L
                override val failed = 0L
                override val rateLimitHits = 0L
                override val degradedMode = false
            }
        val delegate = CapturingNotifier(metrics = m)
        val notifier = FilteringNotifier(emptySet(), delegate)

        assertThat(notifier.metrics).isSameAs(m)
    }

    @Test
    fun `close delegates to underlying notifier`() {
        val delegate = CapturingNotifier()
        val notifier = FilteringNotifier(emptySet(), delegate)

        notifier.close()

        assertThat(delegate.closed).isTrue()
    }
}

class KindOfTest {
    @Test
    fun `kindOf maps every NotificationEvent subtype to expected NotifyEventKind`() {
        assertThat(kindOf(NotificationEvent.OrderRejected("s", "XAUUSD", Side.BUY, BigDecimal.ONE, "r", 0L)))
            .isEqualTo(NotifyEventKind.ORDER_REJECTED)
        assertThat(kindOf(NotificationEvent.Halted(strategyId = null, reason = "r", timestamp = 0L)))
            .isEqualTo(NotifyEventKind.HALTED)
        assertThat(kindOf(NotificationEvent.Resumed(strategyId = null, timestamp = 0L)))
            .isEqualTo(NotifyEventKind.RESUMED)
        assertThat(
            kindOf(
                NotificationEvent.PositionReconciled(
                    strategyId = "s",
                    symbol = "XAUUSD",
                    oldQty = null,
                    newQty = BigDecimal.ONE,
                    reason = "r",
                    timestamp = 0L,
                ),
            ),
        ).isEqualTo(NotifyEventKind.POSITION_RECONCILED)
        assertThat(kindOf(NotificationEvent.StrategyStarted(strategyId = "s", timestamp = 0L)))
            .isEqualTo(NotifyEventKind.STRATEGY_STARTED)
        assertThat(kindOf(NotificationEvent.StrategyStopped(strategyId = "s", flatten = false, timestamp = 0L)))
            .isEqualTo(NotifyEventKind.STRATEGY_STOPPED)
        assertThat(kindOf(NotificationEvent.StrategyError(strategyId = "s", message = "m", timestamp = 0L)))
            .isEqualTo(NotifyEventKind.STRATEGY_ERROR)
        assertThat(kindOf(NotificationEvent.DaemonStarted(version = "1.0", strategies = emptyList(), timestamp = 0L)))
            .isEqualTo(NotifyEventKind.DAEMON_STARTED)
        assertThat(
            kindOf(NotificationEvent.DailySummary(asOfUtc = "2026-06-03", strategies = emptyList(), timestamp = 0L)),
        ).isNull()
    }
}
