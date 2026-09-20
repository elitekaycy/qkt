package com.qkt.app

import com.qkt.app.LiveSessionPersistenceHealthFixtures.awaitCondition
import com.qkt.app.LiveSessionPersistenceHealthFixtures.heldOpenSource
import com.qkt.app.LiveSessionPersistenceHealthFixtures.noOpStrategy
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.events.RiskEvent
import com.qkt.notify.NoopNotifier
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistenceHealth
import com.qkt.persistence.StatePersistor
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionPersistenceHealthStaleStateTest {
    @Test
    fun `failed state write raises a critical alert and entry-only halt`() {
        val persistor =
            object : StatePersistor by NoopStatePersistor() {
                override fun healthSnapshot() =
                    PersistenceHealth(
                        enabled = true,
                        failedWrites = 1L,
                        consecutiveFailures = 1L,
                        failureEpisodes = 1L,
                    )
            }
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val haltSeen = CountDownLatch(1)
        val halts = CopyOnWriteArrayList<RiskEvent.Halted>()
        bus.subscribe<RiskEvent.Halted> {
            halts += it
            haltSeen.countDown()
        }
        val notifications = CopyOnWriteArrayList<NotificationEvent>()
        val notifier =
            object : Notifier {
                override fun notify(event: NotificationEvent) {
                    notifications += event
                }

                override fun close() = Unit
            }
        val closeFeed = CountDownLatch(1)
        val handle =
            LiveSession(
                strategies = listOf("alpha" to noOpStrategy()),
                source = heldOpenSource(closeFeed),
                symbols = listOf("X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                persistor = persistor,
                notifier = notifier,
                notifyEvents = emptySet(),
                busOverride = bus,
                scheduleHeartbeatIntervalMs = 10L,
            ).start()

        assertThat(haltSeen.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(handle.isHalted()).isTrue()
        assertThat(halts).hasSize(1)
        assertThat(halts.single().cancelWorkingOrders).isFalse()
        assertThat(halts.single().reason).contains("durable state is stale")
        assertThat(handle.persistenceHealth().failedWrites).isEqualTo(1L)

        assertThat(awaitCondition { notifications.any { it is NotificationEvent.StrategyError } }).isTrue()
        val errors = notifications.filterIsInstance<NotificationEvent.StrategyError>()
        assertThat(errors).hasSize(1)
        assertThat(errors.single().severity).isEqualTo(NotificationEvent.Severity.CRITICAL)
        assertThat(errors.single().message).contains("persisted state is stale", "new exposure halted")

        closeFeed.countDown()
        handle.stop()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
    }

    @Test
    fun `completed failure episode is not missed between health checks`() {
        val persistor =
            object : StatePersistor by NoopStatePersistor() {
                override fun healthSnapshot() =
                    PersistenceHealth(
                        enabled = true,
                        failedWrites = 1L,
                        consecutiveFailures = 0L,
                        failureEpisodes = 1L,
                    )
            }
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val haltSeen = CountDownLatch(1)
        bus.subscribe<RiskEvent.Halted> { haltSeen.countDown() }
        val closeFeed = CountDownLatch(1)
        val handle =
            LiveSession(
                strategies = listOf("alpha" to noOpStrategy()),
                source = heldOpenSource(closeFeed),
                symbols = listOf("X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                persistor = persistor,
                notifier = NoopNotifier,
                busOverride = bus,
                scheduleHeartbeatIntervalMs = 10L,
            ).start()

        assertThat(haltSeen.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(handle.isHalted()).isTrue()

        closeFeed.countDown()
        handle.stop()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
    }
}
