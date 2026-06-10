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

/**
 * Each deployed strategy gets its own session-local [CandleHub]. A daemon-shared hub
 * would run strategy B's indicator updates and rules on strategy A's engine thread —
 * cross-session concurrency on unsynchronized state.
 */
class PerSessionCandleHubTest {
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

    private val strategySrc =
        """
STRATEGY shared VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 1
        """.trimIndent()

    @Test
    fun `one session's ticks do not run another session's strategy`(
        @TempDir tmp: Path,
    ) {
        val base = 1_705_276_800_000L // top of a minute
        val ticks =
            listOf(
                Tick("BACKTEST:BTCUSDT", BigDecimal("110"), base),
                Tick("BACKTEST:BTCUSDT", BigDecimal("111"), base + 30_000),
                // Cross the minute boundary to close the first candle and fire the rule.
                Tick("BACKTEST:BTCUSDT", BigDecimal("112"), base + 65_000),
            )
        val creates = AtomicInteger(0)
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.toString()),
                // alpha (first create) gets the tick feed; beta's feed is empty.
                marketSourceProvider = {
                    if (creates.getAndIncrement() == 0) FakeSource(ticks) else FakeSource(emptyList())
                },
            )
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
            val alphaFired = CountDownLatch(1)
            alpha.ring.subscribe { if (it.kind == "signal") alphaFired.countDown() }
            if (alpha.ring.snapshot(0, 100).any { it.kind == "signal" }) alphaFired.countDown()
            assertThat(alphaFired.await(5, TimeUnit.SECONDS))
                .describedAs("alpha's own rule fires on its own candle close")
                .isTrue()

            // Under a daemon-shared hub, alpha's candle close would also evaluate
            // beta_strat (same HubKey) and a signal would land in beta's ring.
            assertThat(beta.ring.snapshot(0, 100).filter { it.kind == "signal" }).isEmpty()
        } finally {
            alpha.close()
            beta.close()
        }
    }

    @Test
    fun `a hub delivers each closed candle to every subscriber of its key`(
        @TempDir tmp: Path,
    ) {
        val hub = CandleHub()
        val key = HubKey("BACKTEST", "BTCUSDT", "1m")
        hub.register(key, retention = 5, strategyId = "shared")

        val received = mutableListOf<Pair<String, Long>>()
        hub.onClosed(key, "alpha") { c -> synchronized(received) { received.add("alpha" to c.endTime) } }
        hub.onClosed(key, "beta") { c -> synchronized(received) { received.add("beta" to c.endTime) } }

        val base = 1_705_276_800_000L // top of a minute
        hub.feed(Tick("BACKTEST:BTCUSDT", BigDecimal("100"), base))
        hub.feed(Tick("BACKTEST:BTCUSDT", BigDecimal("110"), base + 30_000))
        // Cross the minute boundary to close the first candle.
        hub.feed(Tick("BACKTEST:BTCUSDT", BigDecimal("120"), base + 65_000))

        synchronized(received) {
            assertThat(received).isNotEmpty
            val grouped = received.groupBy { it.second }
            for ((_, entries) in grouped) {
                assertThat(entries.map { it.first }.toSet()).containsExactlyInAnyOrder("alpha", "beta")
            }
        }
    }
}
