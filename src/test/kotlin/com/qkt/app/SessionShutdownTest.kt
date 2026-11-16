package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.dsl.compile.CandleHub
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How a stop treats a flatten still in progress when the drain wait lapses. */
class SessionShutdownTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val prices = MarketPriceTracker()
    private val mailbox = EngineMailbox()
    private val interrupted = CountDownLatch(1)
    private val engineThread = Thread { runCatching { interrupted.await() } }
    private val heartbeat = Executors.newSingleThreadScheduledExecutor()
    private val feed =
        object : TickFeed {
            override fun next(): Tick? = null
        }
    private val notifier =
        object : Notifier {
            override fun notify(event: NotificationEvent) {}

            override fun close() {}
        }
    private val source =
        object : MarketSource {
            override val name = "mt5"
            override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

            override fun supports(symbol: String) = true
        }
    private val shutdown =
        SessionShutdown(
            strategies = emptyList(),
            mailbox = mailbox,
            thread = engineThread,
            feedThread = Thread {},
            feed = feed,
            brokerStatePoller = null,
            scheduleHeartbeat = heartbeat,
            equityPoller = null,
            builtBrokers = emptyList(),
            riskState =
                RiskState(
                    PnLCalculator(StrategyPositionTracker().account, prices),
                    StrategyPnL(StrategyPositionTracker(), prices),
                    clock,
                    bus,
                ),
            pipelineCandleHub = CandleHub(),
            sessionNotifier = SessionNotifier(notifier, emptySet(), null, emptyList(), clock),
            insights = InsightsLifecycle(null, emptySet(), emptyMap(), emptyList(), source, listOf("X"), clock),
        )

    @Test
    fun `a stop with nothing in flight interrupts the engine thread once the drain lapses`() {
        engineThread.start()

        shutdown.stop()

        assertThat(mailbox.running.get()).isFalse()
        assertThat(engineThread.isInterrupted || !engineThread.isAlive).isTrue()
    }

    @Test
    fun `a stop waits for a flatten still in progress instead of interrupting the engine thread`() {
        engineThread.start()
        mailbox.pendingFlattens.set(1)
        val release = Executors.newSingleThreadScheduledExecutor()
        release.schedule({ mailbox.terminated.countDown() }, 900, TimeUnit.MILLISECONDS)

        shutdown.stop()
        release.shutdownNow()

        assertThat(engineThread.isInterrupted).isFalse()
        assertThat(mailbox.running.get()).isTrue()
        interrupted.countDown()
    }
}
