package com.qkt.cli.daemon

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.HubKey
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SharedCandleHubTest {
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
    fun `two strategies on the same broker symbol timeframe share one hub aggregator`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val sharedHub = CandleHub()
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(emptyList()) },
                candleHub = sharedHub,
            )

        val strategySrc =
            """
STRATEGY shared VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 1
            """.trimIndent()
        val alphaFile =
            tmp.resolve("alpha.qkt").also {
                Files.writeString(it, strategySrc.replace("STRATEGY shared", "STRATEGY alpha_strat"))
            }
        val betaFile =
            tmp.resolve("beta.qkt").also {
                Files.writeString(it, strategySrc.replace("STRATEGY shared", "STRATEGY beta_strat"))
            }

        val alpha = factory.create("alpha", alphaFile, false)
        val beta = factory.create("beta", betaFile, false)
        try {
            assertThat(sharedHub.keys()).hasSize(1)
            val key = HubKey("BACKTEST", "BTCUSDT", "1m")
            assertThat(sharedHub.keys()).contains(key)
        } finally {
            alpha.close()
            beta.close()
        }
    }

    @Test
    fun `shared hub feeds both strategies the same closed candle`(
        @TempDir tmp: Path,
    ) {
        val sharedHub = CandleHub()
        val key = HubKey("BACKTEST", "BTCUSDT", "1m")
        sharedHub.register(key, retention = 5)

        val received = mutableListOf<Pair<String, Long>>()
        sharedHub.onClosed(key) { c -> synchronized(received) { received.add("alpha" to c.endTime) } }
        sharedHub.onClosed(key) { c -> synchronized(received) { received.add("beta" to c.endTime) } }

        // Feed three ticks that span two minute boundaries, closing one or two candles.
        val base = 1_705_276_800_000L // top of a minute
        sharedHub.feed(Tick("BTCUSDT", BigDecimal("100"), base))
        sharedHub.feed(Tick("BTCUSDT", BigDecimal("110"), base + 30_000))
        // Cross the minute boundary to close the first candle.
        sharedHub.feed(Tick("BTCUSDT", BigDecimal("120"), base + 65_000))

        synchronized(received) {
            // Each closed candle is delivered to both listeners with the same endTime.
            assertThat(received).isNotEmpty
            val grouped = received.groupBy { it.second }
            for ((_, entries) in grouped) {
                assertThat(entries.map { it.first }.toSet()).containsExactlyInAnyOrder("alpha", "beta")
            }
        }
    }
}
