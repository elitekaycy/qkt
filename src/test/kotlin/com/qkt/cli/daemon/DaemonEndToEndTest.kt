package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonEndToEndTest {
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

    private fun waitForFile(
        path: Path,
        deadlineMs: Long = 5_000L,
    ) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (Files.exists(path)) return
            Thread.sleep(50)
        }
        throw AssertionError("file did not appear in $deadlineMs ms: $path")
    }

    @Test
    fun `full daemon lifecycle in-process`(
        @TempDir tmp: Path,
    ) {
        val ticks =
            (0 until 4).map {
                Tick(
                    symbol = "BTCUSDT",
                    price = BigDecimal("42000.0").add(BigDecimal(it * 10)),
                    timestamp = 1_705_276_800_000L + it * 60_000L,
                )
            }
        val daemonThread =
            Thread {
                val args = Args(arrayOf("daemon", "--state-dir", tmp.toString()))
                DaemonCommand(args, sourceFactory = { FakeSource(ticks) }).run()
            }
        daemonThread.isDaemon = true
        daemonThread.start()

        try {
            val stateDir = StateDir.resolve(tmp.toString())
            waitForFile(stateDir.controlPortFile)
            val client = ControlClient(stateDir)
            val http = OkHttpClient()

            // 1. Deploy a strategy
            val deployResp = client.deploy("ema", Path.of("src/test/resources/cli/valid_strategy.qkt"))
            assertThat(deployResp).contains("\"name\":\"ema\"")

            // 2. List should show one strategy
            val listResp = client.list()
            assertThat(listResp).contains("\"name\":\"ema\"")
            val portMatch = Regex("\"port\":(\\d+)").find(listResp)
            val strategyPort = portMatch!!.groupValues[1].toInt()
            assertThat(strategyPort).isGreaterThan(0)

            // 3. /status proxies through to the strategy's observability port
            val statusBody = client.status("ema")
            assertThat(statusBody).contains("\"strategy\":\"example\"")

            // 4. Direct hit on the strategy's observability port also works
            val direct =
                http
                    .newCall(Request.Builder().url("http://127.0.0.1:$strategyPort/status").build())
                    .execute()
            assertThat(direct.code).isEqualTo(200)
            direct.close()

            // 5. Stop the strategy
            client.stop("ema")
            assertThat(client.list().trim()).isEqualTo("[]")

            // 6. Shut down the daemon
            client.shutdown()
            daemonThread.join(Duration.ofSeconds(5).toMillis())
            assertThat(daemonThread.isAlive).isFalse
            assertThat(Files.exists(stateDir.controlPortFile)).isFalse
        } finally {
            if (daemonThread.isAlive) {
                daemonThread.interrupt()
                daemonThread.join(2_000)
            }
        }
    }
}
