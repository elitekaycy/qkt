package com.qkt.notify

import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailySummaryScheduledTest {
    @Test
    fun `fires summaryProducer at the configured cadence and enqueues to notifier`() {
        val latch = CountDownLatch(2)
        val captured = mutableListOf<NotificationEvent>()
        val notifier =
            object : Notifier {
                override fun notify(event: NotificationEvent) {
                    captured.add(event)
                    latch.countDown()
                }

                override fun close() {}
            }
        val producer = {
            NotificationEvent.DailySummary(
                asOfUtc = "2026-05-17",
                strategies =
                    listOf(
                        StrategySummary(
                            strategyId = "x",
                            equity = BigDecimal("10000"),
                            equityDeltaPct = BigDecimal.ZERO,
                            realizedToday = BigDecimal.ZERO,
                            unrealized = BigDecimal.ZERO,
                            tradesToday = 0,
                            haltsToday = 0,
                            positionsSummary = "flat",
                        ),
                    ),
                timestamp = 1L,
            )
        }
        val s =
            DailySummaryScheduler(
                notifier = notifier,
                producer = producer,
                periodMs = 50L,
            )
        s.start(initialDelayMs = 0L)

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(captured).hasSizeGreaterThanOrEqualTo(2)
        assertThat(captured.first()).isInstanceOf(NotificationEvent.DailySummary::class.java)
        s.close()
    }

    @Test
    fun `close cancels further executions`() {
        val captured = mutableListOf<NotificationEvent>()
        val notifier =
            object : Notifier {
                override fun notify(event: NotificationEvent) {
                    captured.add(event)
                }

                override fun close() {}
            }
        val producer = {
            NotificationEvent.DailySummary("d", emptyList(), 1L)
        }
        val s = DailySummaryScheduler(notifier = notifier, producer = producer, periodMs = 50L)
        s.start(initialDelayMs = 0L)
        Thread.sleep(120)
        s.close()
        val countAtClose = captured.size
        Thread.sleep(150)
        assertThat(captured.size).isEqualTo(countAtClose)
    }
}
