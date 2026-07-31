package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.PaperBroker
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionBrokerEquityTest {
    @Test
    fun `modeled equity basis does not poll or consume venue account equity`() {
        val closeFeed = CountDownLatch(1)
        val accountReads = AtomicInteger()
        val factory: BrokerFactory = { bus, clock, prices, _, _ ->
            object : Broker by PaperBroker(bus, clock, prices) {
                override val supportsAccountEquity = true

                override fun accountEquity(): BigDecimal? {
                    accountReads.incrementAndGet()
                    return BigDecimal("99999")
                }
            }
        }
        val handle =
            LiveSession(
                strategies = listOf("alpha" to testStrategy()),
                source = heldOpenSource(closeFeed),
                symbols = listOf("EXNESS:X"),
                clock = FixedClock(0L),
                calendar = TradingCalendar.crypto(),
                brokerFactories = mapOf("exness" to factory),
                initialBalance = BigDecimal("10000"),
                equityBasis = LiveEquityBasis.MODELED,
                brokerEquityPollMs = 10L,
            ).start()

        Thread.sleep(50L)
        assertThat(accountReads.get()).isZero()
        assertThat(handle.pnlSnapshot("alpha").equity).isEqualByComparingTo("10000")
        closeFeed.countDown()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
    }

    @Test
    fun `startup equity failures retry alert and recover without changing basis to derived`() {
        val closeFeed = CountDownLatch(1)
        val source = heldOpenSource(closeFeed)
        val strategy = testStrategy()
        val accountReads = AtomicInteger()
        val factory: BrokerFactory = { bus, clock, prices, _, _ ->
            object : Broker by PaperBroker(bus, clock, prices) {
                override val supportsAccountEquity = true
                override val name = "equity-broker"

                override fun accountEquity(): BigDecimal? =
                    if (accountReads.incrementAndGet() <= 3) null else BigDecimal("12345")
            }
        }
        val notifications = CopyOnWriteArrayList<NotificationEvent>()
        val notifier =
            object : Notifier {
                override fun notify(event: NotificationEvent) {
                    notifications.add(event)
                }

                override fun close() = Unit
            }
        val handle =
            LiveSession(
                strategies = listOf("alpha" to strategy),
                source = source,
                symbols = listOf("EXNESS:X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(0L),
                calendar = TradingCalendar.crypto(),
                brokerFactories = mapOf("exness" to factory),
                notifier = notifier,
                notifyEvents = setOf(NotifyEventKind.STRATEGY_ERROR),
                brokerEquityPollMs = 10L,
                brokerEquityStaleMs = 0L,
            ).start()

        val deadline = System.currentTimeMillis() + 2_000L
        while (accountReads.get() < 4 && System.currentTimeMillis() < deadline) Thread.sleep(5L)
        assertThat(accountReads.get()).isGreaterThanOrEqualTo(4)
        assertThat(handle.pnlSnapshot("alpha").equity).isEqualByComparingTo("12345")

        val notificationDeadline = System.currentTimeMillis() + 2_000L
        while (notifications.isEmpty() && System.currentTimeMillis() < notificationDeadline) Thread.sleep(5L)
        closeFeed.countDown()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        val errors = notifications.filterIsInstance<NotificationEvent.StrategyError>()
        assertThat(errors).hasSize(1)
        assertThat(errors.single().message)
            .contains("unavailable for 3 consecutive polls")
            .contains("drawdown basis is stale")
    }

    private fun heldOpenSource(closeFeed: CountDownLatch): MarketSource =
        object : MarketSource {
            override val name = "held-open"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true

            override fun liveTicks(symbols: List<String>): TickFeed =
                object : TickFeed {
                    private var first = true

                    override fun next(): Tick? {
                        if (first) {
                            first = false
                            return Tick("EXNESS:X", Money.of("100"), 1_000L)
                        }
                        closeFeed.await(2, TimeUnit.SECONDS)
                        return null
                    }

                    override fun close() {
                        closeFeed.countDown()
                    }
                }
        }

    private fun testStrategy(): DslCompiledStrategy =
        object : DslCompiledStrategy {
            override val declaredStreams = mapOf("x" to HubKey("EXNESS", "X", "1m"))
            override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
            override val retentionByKey: Map<HubKey, Int> = emptyMap()
            override val pendingStacks = PendingStacks()

            override fun bindToHub(
                hub: CandleHub,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }
}
