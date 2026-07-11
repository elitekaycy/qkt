package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.live.LiveTickSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionFeedLossTest {
    @Test
    fun `expired live reconnect budget emits one critical stop notification`() {
        val notifier = CapturingNotifier()
        val handle = startExpiredFeed(notifier, setOf(NotifyEventKind.STRATEGY_STOPPED))

        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        val stopped = notifier.events.filterIsInstance<NotificationEvent.StrategyStopped>()
        assertThat(stopped).hasSize(1)
        assertThat(stopped.single().unexpected).isTrue()
        assertThat(stopped.single().severity).isEqualTo(NotificationEvent.Severity.CRITICAL)
        assertThat(stopped.single().reason).contains("reconnect budget")

        handle.stop()
    }

    @Test
    fun `expired live reconnect budget falls back to critical strategy error`() {
        val notifier = CapturingNotifier()
        val handle = startExpiredFeed(notifier, setOf(NotifyEventKind.STRATEGY_ERROR))

        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        val errors = notifier.events.filterIsInstance<NotificationEvent.StrategyError>()
        assertThat(errors).hasSize(1)
        assertThat(errors.single().severity).isEqualTo(NotificationEvent.Severity.CRITICAL)
        assertThat(errors.single().message).contains("reconnect budget")

        handle.stop()
    }

    @Test
    fun `heartbeat pages when a live symbol becomes unhealthy`() {
        val clock = FixedClock(1L)
        val notifier = CapturingNotifier()
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val handle =
            LiveSession(
                strategies = listOf("live" to strategy),
                source = OutlierFeedSource(),
                symbols = listOf("X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                notifier = notifier,
                notifyEvents = setOf(NotifyEventKind.STRATEGY_ERROR),
                scheduleHeartbeatIntervalMs = 5L,
            ).start()

        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(notifier.events.filterIsInstance<NotificationEvent.StrategyError>())
            .anyMatch { it.message.contains("market data unhealthy for X") }

        handle.stop()
    }

    private fun startExpiredFeed(
        notifier: CapturingNotifier,
        events: Set<NotifyEventKind>,
    ): LiveSessionHandle {
        val clock = FixedClock(1L)
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        return LiveSession(
            strategies = listOf("live" to strategy),
            source = ExpiredFeedSource(clock),
            symbols = listOf("X"),
            clock = clock,
            calendar = TradingCalendar.crypto(),
            notifier = notifier,
            notifyEvents = events,
            scheduleHeartbeatIntervalMs = 10L,
        ).start()
    }

    private class CapturingNotifier : Notifier {
        val events = CopyOnWriteArrayList<NotificationEvent>()

        override fun notify(event: NotificationEvent) {
            events.add(event)
        }

        override fun close() = Unit
    }

    private class ExpiredFeedSource(
        private val clock: FixedClock,
    ) : MarketSource {
        override val name: String = "expired-live-feed"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            LiveTickFeed(
                source =
                    object : LiveTickSource {
                        override fun start(
                            onTick: (Tick) -> Unit,
                            onError: (Throwable) -> Unit,
                            onDisconnect: () -> Unit,
                            onReconnect: () -> Unit,
                        ) {
                            onDisconnect()
                            clock.advanceTo(100L)
                        }

                        override fun stop() = Unit
                    },
                pollIntervalMs = 1L,
                clock = clock,
                reconnectBudgetMs = 10L,
            )
    }

    private class OutlierFeedSource : MarketSource {
        override val name: String = "outlier-feed"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed {
            val ticks =
                (1L..20L).map { i ->
                    Tick("X", Money.of(if (i % 2L == 0L) "100.0" else "100.2"), i * 100L)
                } + Tick("X", Money.of("250"), 2_100L)
            return object : TickFeed {
                private val index = AtomicInteger()
                private val closed = AtomicBoolean()

                override fun next(): Tick? {
                    val i = index.getAndIncrement()
                    if (i < ticks.size) return ticks[i]
                    if (!closed.get()) Thread.sleep(100L)
                    return null
                }

                override fun close() {
                    closed.set(true)
                }
            }
        }
    }
}
