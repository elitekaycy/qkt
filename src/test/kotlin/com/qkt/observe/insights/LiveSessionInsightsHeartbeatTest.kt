package com.qkt.observe.insights

import com.qkt.app.LiveSession
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionInsightsHeartbeatTest : LiveSessionInsightsFixture() {
    private class TickThenHoldSource : MarketSource {
        override val name = "connected-but-frozen"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)
        val delivered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                private val emitted = AtomicBoolean(false)

                override fun next(): Tick? {
                    if (emitted.compareAndSet(false, true)) {
                        delivered.countDown()
                        return Tick("X", Money.of("100"), 1L)
                    }
                    released.await()
                    return null
                }

                override fun close() {
                    released.countDown()
                }
            }
    }

    @Test
    fun `heartbeat emits per-symbol staleness while a position is open`() {
        val clock = FixedClock(1L)
        val source = TickThenHoldSource()
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 20L,
                queueCapacity = 1000,
            )
        val entered = AtomicBoolean(false)
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (entered.compareAndSet(false, true)) emit(Signal.Buy(tick.symbol, Money.of("1")))
                }
            }
        val handle =
            LiveSession(
                strategies = listOf("stale-position" to strategy),
                source = source,
                symbols = listOf("X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                insightsSink = sink,
                insightsEvents = setOf(InsightsEventFamily.LIFECYCLE),
                scheduleHeartbeatIntervalMs = 5L,
            ).start()

        try {
            assertThat(source.delivered.await(2, TimeUnit.SECONDS)).isTrue()
            val positionDeadline = System.currentTimeMillis() + 2_000L
            while (handle.positionsFor("stale-position").isEmpty() && System.currentTimeMillis() < positionDeadline) {
                Thread.sleep(5L)
            }
            assertThat(handle.positionsFor("stale-position")).isNotEmpty

            clock.advanceTo(20_001L)
            val bodies = StringBuilder()
            val deadline = System.currentTimeMillis() + 2_000L
            while (!bodies.contains("\"type\":\"marketdata.stale\"") && System.currentTimeMillis() < deadline) {
                server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { bodies.append(it.body.readUtf8()) }
            }

            assertThat(bodies.toString()).contains("\"type\":\"marketdata.stale\"")
            assertThat(bodies.toString()).contains("\"source\":\"connected-but-frozen\"")
            assertThat(bodies.toString()).contains("\"symbols\":[\"X\"]")
        } finally {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(2))
            sink.close()
        }
    }
}
