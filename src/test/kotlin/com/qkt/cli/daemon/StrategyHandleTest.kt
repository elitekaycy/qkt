package com.qkt.cli.daemon

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyHandleTest {
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
    fun `RealFactory builds a handle with non-zero port and a log file`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val ticks =
            (0 until 3).map {
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
            assertThat(handle.name).isEqualTo("alpha")
            assertThat(handle.port).isGreaterThan(0)
            assertThat(handle.isRunning()).isTrue
            assertThat(Files.exists(handle.logFile)).isTrue
            assertThat(handle.logFile).isEqualTo(stateDir.logFile("alpha"))

            // The observability /status endpoint reflects this strategy's name.
            val client = OkHttpClient()
            val resp =
                client
                    .newCall(Request.Builder().url("http://127.0.0.1:${handle.port}/status").build())
                    .execute()
            assertThat(resp.code).isEqualTo(200)
            val body = resp.body!!.string()
            assertThat(body).contains("\"strategy\":\"example\"")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `RealFactory raises a clear error on parse failure`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(emptyList()) },
            )
        val broken = tmp.resolve("broken.qkt").also { Files.writeString(it, "STRATEGY") }
        val err = runCatching { factory.create("bad", broken, false) }.exceptionOrNull()
        assertThat(err).isNotNull
        assertThat(err!!.message).contains("parse failure")
    }
}
