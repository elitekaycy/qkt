package com.qkt.observe.insights

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The state poller's per-strategy equity sample reads the session handle, which start() publishes only after the
 * poller's first pass may already have run. A short poll interval over a feed that stays open until stop() makes a
 * pass after the handle certain, so the sample's path never depends on that race.
 */
class LiveSessionStateEquityPollTest : LiveSessionInsightsFixture() {
    @Test
    fun `state poller samples per-strategy equity once the session handle exists`() {
        val now = Instant.parse("2024-01-15T15:00:00Z")
        val gate = CountDownLatch(1)
        val src =
            object : InMemoryMarketSource() {
                override fun liveTicks(symbols: List<String>): TickFeed =
                    object : TickFeed {
                        override fun next(): Tick? {
                            gate.await(30, TimeUnit.SECONDS)
                            return null
                        }

                        override fun close() = gate.countDown()
                    }
            }
        src.seedLive("X", emptyList())
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 50L,
                queueCapacity = 1000,
            )
        val idle =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) = Unit
            }
        val handle =
            LiveSession(
                strategies = listOf("idle" to idle),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                insightsSink = sink,
                insightsEvents = InsightsEventFamily.entries.toSet(),
                insightsStatePollMs = 50L,
            ).start()

        val bodies = StringBuilder()
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { bodies.append(it.body.readUtf8()) }
                if (bodies.contains("\"type\":\"snapshot.equity\"")) break
            }
        } finally {
            handle.stop()
            gate.countDown()
            handle.awaitTermination(Duration.ofSeconds(5))
            sink.close()
        }

        assertThat(bodies.toString()).contains("\"type\":\"snapshot.equity\"")
        assertThat(bodies.toString()).contains("\"strategyId\":\"idle\"")
    }
}
