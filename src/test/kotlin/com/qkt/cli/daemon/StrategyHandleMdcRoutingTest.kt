package com.qkt.cli.daemon

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.app.OrderManager
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

/**
 * Regression for the empty-per-strategy-log-file bug (#100).
 *
 * Before the fix, the `onTrade` / `onSignal` callbacks wired in `StrategyHandle` did
 * `MDC.put("strategy", name)` and `MDC.remove("strategy")` in a `finally`. Because the
 * callbacks run on `qkt-live-engine` — which already holds an outer `strategy` MDC value
 * set by `LiveSession.start()` — the `remove` wiped the outer value, and every subsequent
 * log line from that thread routed to `main.log` instead of the per-strategy file.
 *
 * The fix replaces put + remove with `withMdc`, which saves the outer value and restores
 * it. This test drives a strategy that signals on every tick (so onSignal fires) and then
 * asserts that the broker-publish path's `OrderManager` log lines — which run on
 * `qkt-live-engine` after the callback returns — still carry `MDC.strategy = "alpha"`.
 */
class StrategyHandleMdcRoutingTest {
    private val orderManagerLogger = LoggerFactory.getLogger(OrderManager::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        orderManagerLogger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        orderManagerLogger.detachAppender(appender)
        appender.stop()
    }

    private class BoundedFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)
        private val gate = CountDownLatch(1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i < ticks.size) return ticks[i]
            gate.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            gate.countDown()
        }
    }

    private class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "Fake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = BoundedFeed(ticks)
    }

    @Test
    fun `OrderManager logs from qkt-live-engine carry MDC strategy after onSignal callback`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val ticks =
            (0 until 5).map {
                Tick(
                    symbol = "BACKTEST:BTCUSDT",
                    price = BigDecimal("42000.0").add(BigDecimal(it * 10)),
                    timestamp = 1_705_276_800_000L + it * 60_000L,
                )
            }
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(ticks) },
            )
        val file = Path.of("src/test/resources/cli/valid_strategy.qkt")
        val handle = factory.create("alpha", file, false)
        try {
            // ListAppender.list is a plain ArrayList that the qkt-live-engine thread
            // mutates concurrently while we iterate. Snapshot via toList() before each
            // scan so we don't ConcurrentModificationException mid-iteration. See #157.
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline &&
                appender.list.toList().none { it.formattedMessage.contains("order accepted") }
            ) {
                Thread.sleep(50)
            }

            val accepts =
                appender.list.toList().filter {
                    it.formattedMessage.contains("order accepted") && it.threadName == "qkt-live-engine"
                }
            assertThat(accepts)
                .`as`("at least one 'order accepted' should be logged on qkt-live-engine")
                .isNotEmpty
            assertThat(accepts).allSatisfy { e ->
                // The MDC carries the DSL strategy id ("example" from valid_strategy.qkt),
                // not the deploy name ("alpha") — log attribution must match the id
                // every trading event uses.
                assertThat(e.mdcPropertyMap["strategy"])
                    .`as`("MDC.strategy must survive the onSignal callback on qkt-live-engine")
                    .isEqualTo("example")
            }
        } finally {
            handle.close()
        }
    }
}
