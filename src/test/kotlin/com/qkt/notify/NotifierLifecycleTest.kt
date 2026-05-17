package com.qkt.notify

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.notify.TelegramClient.Outcome
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotifierLifecycleTest {
    @Test
    fun `bus OrderRejected with context routes a CRITICAL message to Telegram`() {
        val captured = mutableListOf<String>()
        val metrics = AtomicNotifierMetrics()
        val worker =
            NotificationWorker(
                send = { text ->
                    captured.add(text)
                    Outcome.Ok
                },
                metrics = metrics,
                backoffMs = listOf(1L),
            )
        val notifier = TelegramNotifier(worker = worker, metrics = metrics)
        val bus = EventBus(clock = FixedClock(1L), sequencer = MonotonicSequenceGenerator())

        val recentOrders = mutableMapOf<String, Triple<String, Side, BigDecimal>>()
        recentOrders["c1"] = Triple("EXNESS:XAUUSD", Side.BUY, BigDecimal("0.24"))

        bus.subscribe<BrokerEvent.OrderRejected> { ev ->
            val ctx = recentOrders[ev.clientOrderId] ?: return@subscribe
            notifier.notify(
                EventTranslator.fromBrokerRejected(
                    event = ev,
                    symbol = ctx.first,
                    side = ctx.second,
                    quantity = ctx.third,
                ),
            )
        }
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = null,
                reason = "10015 invalid price",
                strategyId = "hedge-straddle",
                timestamp = 1L,
            ),
        )

        worker.flush(timeoutMs = 1_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured.first()).contains("[CRITICAL] qkt order rejected")
        notifier.close()
    }

    @Test
    fun `bus RiskHalted routes a CRITICAL message`() {
        val captured = mutableListOf<String>()
        val metrics = AtomicNotifierMetrics()
        val worker =
            NotificationWorker(
                send = { text ->
                    captured.add(text)
                    Outcome.Ok
                },
                metrics = metrics,
                backoffMs = listOf(1L),
            )
        val notifier = TelegramNotifier(worker = worker, metrics = metrics)
        val bus = EventBus(clock = FixedClock(1L), sequencer = MonotonicSequenceGenerator())
        bus.subscribe<RiskEvent.Halted> { ev -> notifier.notify(EventTranslator.fromRiskHalted(ev)) }

        bus.publish(RiskEvent.Halted(reason = "MaxDrawdown", strategyId = null, timestamp = 1L))
        worker.flush(timeoutMs = 1_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured.first()).contains("[CRITICAL] qkt HALTED (global)")
        notifier.close()
    }

    @Test
    fun `subscriber catches notifier failure and does not re-throw on the bus`() {
        val notifier =
            object : Notifier {
                override fun notify(event: NotificationEvent) = error("boom")

                override fun close() {}
            }
        val bus = EventBus(clock = FixedClock(1L), sequencer = MonotonicSequenceGenerator())
        bus.subscribe<RiskEvent.Resumed> { ev ->
            try {
                notifier.notify(EventTranslator.fromRiskResumed(ev))
            } catch (t: Throwable) {
                // swallow — the LiveSession wiring uses the same runCatching pattern
            }
        }
        // bus.publish must not throw even though the inner notifier does
        bus.publish(RiskEvent.Resumed(strategyId = "x", timestamp = 1L))
    }
}
