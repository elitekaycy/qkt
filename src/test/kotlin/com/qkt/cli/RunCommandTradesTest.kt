package com.qkt.cli

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunCommandTradesTest {
    private class Feed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)
        private val closed = CountDownLatch(1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i < ticks.size) return ticks[i]
            closed.await(30, TimeUnit.SECONDS)
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

    /** Stdout that completes [port] on the `QKT_PORT=` line and [fill] on the first printed fill. */
    private class WatchingOut : OutputStream() {
        val text = ByteArrayOutputStream()
        val port = CompletableFuture<Int>()
        val fill = CompletableFuture<String>()

        @Synchronized
        override fun write(b: Int) {
            text.write(b)
            if (b != '\n'.code) return
            val lines = text.toString().lines()
            lines
                .firstOrNull {
                    it.startsWith(
                        "QKT_PORT=",
                    )
                }?.let { port.complete(it.removePrefix("QKT_PORT=").trim().toInt()) }
            lines.firstOrNull { it.startsWith("[INFO] ") && " BUY BTCUSDT " in it }?.let { fill.complete(it) }
        }
    }

    @Test
    fun `a fill is printed and served on the status endpoint`() {
        val ticks =
            (0 until 6).map {
                Tick(
                    "BTCUSDT",
                    BigDecimal("42000").add(BigDecimal(it * 10)),
                    1_705_276_800_000L + it * 60_000L,
                )
            }
        val out = WatchingOut()
        val saved = System.out
        val savedErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(ByteArrayOutputStream(), true))
        val code = CompletableFuture<Int>()
        val runner =
            Thread {
                val args = Args(arrayOf("run", "src/test/resources/cli/valid_strategy.qkt", "--port", "0"))
                code.complete(RunCommand(args) { Source(Feed(ticks)) }.run())
            }.apply {
                isDaemon = true
                start()
            }
        try {
            val port = out.port.get(20, TimeUnit.SECONDS)
            val line = out.fill.get(20, TimeUnit.SECONDS)
            assertThat(line).contains("qty=1").contains("px=").contains("realized=")

            val http = OkHttpClient()
            val status =
                http.newCall(Request.Builder().url("http://127.0.0.1:$port/status").build()).execute().use {
                    it.body!!.string()
                }
            assertThat(
                status,
            ).contains("\"lastTrade\":{").contains("\"symbol\":\"BTCUSDT\"").contains("\"side\":\"BUY\"")
            val stop =
                http
                    .newCall(
                        Request
                            .Builder()
                            .url("http://127.0.0.1:$port/stop")
                            .post(ByteArray(0).toRequestBody(null))
                            .build(),
                    ).execute()
            assertThat(stop.use { it.code }).isEqualTo(202)
            assertThat(code.get(20, TimeUnit.SECONDS)).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(saved)
            System.setErr(savedErr)
            runner.interrupt()
        }
    }
}
