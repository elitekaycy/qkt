package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NoopNotifier
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistenceHealth
import com.qkt.persistence.StatePersistor
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionPersistenceHealthTest {
    private class MutableHealthPersistor : StatePersistor by NoopStatePersistor() {
        val failedWrites = AtomicLong(0L)

        override fun healthSnapshot(): PersistenceHealth {
            val failures = failedWrites.get()
            return PersistenceHealth(
                enabled = true,
                failedWrites = failures,
                consecutiveFailures = failures,
                failureEpisodes = if (failures == 0L) 0L else 1L,
            )
        }
    }

    private class EntryStrategy : Strategy {
        val emitEntry = AtomicBoolean(false)

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (emitEntry.get()) emit(Signal.Buy(tick.symbol, Money.of("1")))
        }
    }

    private class ControllableSource : MarketSource {
        private val queue = LinkedBlockingQueue<Any>()
        private val end = Any()

        override val name = "controllable"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        fun emit(tick: Tick) {
            queue.put(tick)
        }

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next(): Tick? {
                    val value = queue.take()
                    return if (value === end) null else value as Tick
                }

                override fun close() {
                    queue.offer(end)
                }
            }
    }

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

    @Test
    fun `persistence halt keeps working orders and rejects later entry signals`() {
        val persistor = MutableHealthPersistor()
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val rejectionSeen = CountDownLatch(1)
        bus.subscribe<RiskRejectedEvent> { rejectionSeen.countDown() }
        val accepted = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        bus.subscribe<BrokerEvent.OrderAccepted> { accepted += it.clientOrderId }
        bus.subscribe<BrokerEvent.OrderCancelled> { cancelled += it.clientOrderId }
        val strategy = EntryStrategy()
        val source = ControllableSource()
        val handle =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = source,
                symbols = listOf("EXNESS:X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                persistor = persistor,
                notifier = NoopNotifier,
                busOverride = bus,
                scheduleHeartbeatIntervalMs = 10L,
            ).start()
        try {
            val protectiveStop =
                OrderRequest.Stop(
                    id = "protective-stop",
                    symbol = "EXNESS:X",
                    side = Side.SELL,
                    quantity = Money.of("1"),
                    stopPrice = Money.of("90"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = clock.now(),
                    strategyId = "alpha",
                )
            bus.publish(OrderEvent(protectiveStop))
            assertThat(awaitCondition { protectiveStop.id in accepted }).isTrue()

            persistor.failedWrites.incrementAndGet()
            assertThat(awaitCondition { handle.isHalted() }).isTrue()
            Thread.sleep(50L)
            assertThat(cancelled).isEmpty()

            strategy.emitEntry.set(true)
            source.emit(Tick("EXNESS:X", Money.of("100"), 1_000L))
            assertThat(rejectionSeen.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(accepted).containsExactly("protective-stop")
        } finally {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(2))
        }
    }

    private fun noOpStrategy(): Strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5L)
        return condition()
    }

    private fun heldOpenSource(closeFeed: CountDownLatch): MarketSource =
        object : MarketSource {
            override val name = "held-open"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    override fun next(): Tick? {
                        closeFeed.await()
                        return null
                    }

                    override fun close() {
                        closeFeed.countDown()
                    }
                }
        }
}
