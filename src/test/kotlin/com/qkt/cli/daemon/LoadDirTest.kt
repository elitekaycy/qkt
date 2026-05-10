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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LoadDirTest {
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
    fun `--load-dir auto-deploys every qkt file in the directory`(
        @TempDir tmp: Path,
    ) {
        val strategiesDir = Files.createDirectories(tmp.resolve("strategies"))

        fun strategySource(internalName: String) =
            """
STRATEGY $internalName VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 1
            """.trimIndent()
        Files.writeString(strategiesDir.resolve("alpha.qkt"), strategySource("alpha_strategy"))
        Files.writeString(strategiesDir.resolve("beta.qkt"), strategySource("beta_strategy"))
        Files.writeString(strategiesDir.resolve("README.txt"), "should be ignored")

        val ticks =
            (0 until 2).map {
                Tick(
                    symbol = "BTCUSDT",
                    price = BigDecimal("42000.0").add(BigDecimal(it * 10)),
                    timestamp = 1_705_276_800_000L + it * 60_000L,
                )
            }

        val daemonThread =
            Thread {
                val args =
                    Args(
                        arrayOf(
                            "daemon",
                            "--state-dir",
                            tmp.resolve("state").toString(),
                            "--load-dir",
                            strategiesDir.toString(),
                        ),
                    )
                DaemonCommand(args, sourceFactory = { FakeSource(ticks) }).run()
            }
        daemonThread.isDaemon = true
        daemonThread.start()

        try {
            val stateDir = StateDir.resolve(tmp.resolve("state").toString())
            waitForFile(stateDir.controlPortFile)
            val port = stateDir.readControlPort()!!
            val client = OkHttpClient()
            val deadline = System.currentTimeMillis() + 30_000
            var body: String = ""
            while (System.currentTimeMillis() < deadline) {
                val resp =
                    client
                        .newCall(Request.Builder().url("http://127.0.0.1:$port/list").build())
                        .execute()
                body = resp.body!!.string()
                if (body.contains("\"name\":\"alpha\"") && body.contains("\"name\":\"beta\"")) break
                Thread.sleep(100)
            }
            assertThat(body).contains("\"name\":\"alpha\"")
            assertThat(body).contains("\"name\":\"beta\"")
        } finally {
            daemonThread.interrupt()
            daemonThread.join(5_000)
        }
    }
}
