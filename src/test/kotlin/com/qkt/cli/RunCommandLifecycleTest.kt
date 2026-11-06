package com.qkt.cli

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunCommandLifecycleTest {
    private class Feed(
        private val ticks: List<Tick>,
        private val holdOpen: Boolean,
    ) : TickFeed {
        private val idx = AtomicInteger(0)
        private val closed = CountDownLatch(1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i < ticks.size) return ticks[i]
            if (holdOpen) closed.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() = closed.countDown()
    }

    private class Source(
        private val feed: TickFeed,
    ) : MarketSource {
        override val name = "Fake"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>) = feed
    }

    /** Stdout that completes [port] as soon as the run announces its `QKT_PORT=` line. */
    private class AnnouncingOut : OutputStream() {
        val text = ByteArrayOutputStream()
        val port = CompletableFuture<Int>()

        @Synchronized
        override fun write(b: Int) {
            text.write(b)
            if (b == '\n'.code) {
                text.toString().lineSequence().firstOrNull { it.startsWith("QKT_PORT=") }?.let {
                    port.complete(it.removePrefix("QKT_PORT=").trim().toInt())
                }
            }
        }
    }

    private fun ticks(symbol: String) =
        (0 until 6).map {
            Tick(
                symbol,
                BigDecimal("42000").add(BigDecimal(it * 10)),
                1_705_276_800_000L + it * 60_000L,
            )
        }

    @Test
    fun `health latency and a flattening stop are served while the run is live`() {
        val out = AnnouncingOut()
        val saved = System.out
        val savedErr = System.err
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        val code = CompletableFuture<Int>()
        val runner =
            Thread {
                val args =
                    Args(
                        arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--port", "0", "--flatten-on-stop"),
                    )
                code.complete(RunCommand(args) { Source(Feed(ticks("BTCUSDT"), holdOpen = true)) }.run())
            }.apply {
                isDaemon = true
                start()
            }
        try {
            val port = out.port.get(20, TimeUnit.SECONDS)
            val http = OkHttpClient()

            fun get(path: String) = http.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute()

            assertThat(get("/health").use { it.code }).isEqualTo(200)
            assertThat(get("/latency").use { it.code to it.body!!.string() }.first).isEqualTo(200)
            val status = get("/status").use { it.body!!.string() }
            assertThat(status).contains("\"strategy\":\"example\"").contains("\"pendingStackLayers\":[]")
            val stop =
                http
                    .newCall(
                        Request
                            .Builder()
                            .url("http://127.0.0.1:$port/stop?flatten=true")
                            .post(ByteArray(0).toRequestBody(null))
                            .build(),
                    ).execute()
            assertThat(stop.use { it.code }).isEqualTo(202)

            assertThat(code.get(20, TimeUnit.SECONDS)).isEqualTo(ExitCodes.SUCCESS)
            assertThat(err.toString()).contains("flatten-on-stop requested via /stop")
        } finally {
            System.setOut(saved)
            System.setErr(savedErr)
            runner.interrupt()
        }
    }

    @Test
    fun `a file that does not parse is reported line by line`(
        @TempDir tmp: Path,
    ) {
        val broken = tmp.resolve("broken.qkt")
        Files.writeString(broken, "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")

        val run =
            captureCliRun { RunCommand(Args(arrayOf("run", broken.toString()))) { error("no feed expected") }.run() }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).containsPattern("broken\\.qkt:\\d+:\\d+ — ").containsPattern("\\d+ errors?\\n")
    }
}
