package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A strategy the daemon deploys answers health, latency and stop on its own observability port. */
class DaemonStrategyServerTest {
    private class IdleFeed : TickFeed {
        private val closed = CountDownLatch(1)

        override fun next(): Tick? {
            closed.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() = closed.countDown()
    }

    private class IdleSource : MarketSource {
        override val name = "Idle"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed = IdleFeed()
    }

    @Test
    fun `a deployed strategy serves health latency and a stop request`(
        @TempDir tmp: Path,
    ) {
        val load = Files.createDirectories(tmp.resolve("load"))
        Files.writeString(
            load.resolve("alpha.qkt"),
            "STRATEGY alpha_s VERSION 1\nSYMBOLS\n    btc = BACKTEST:BTCUSDT EVERY 1m\nRULES\n" +
                "    WHEN btc.close > 100 THEN BUY btc SIZING 1\n",
        )
        val state = tmp.resolve("state")
        val daemon =
            Thread {
                val args = Args(arrayOf("daemon", "--state-dir", state.toString(), "--load-dir", load.toString()))
                DaemonCommand(args, sourceFactory = { IdleSource() }).run()
            }.apply {
                isDaemon = true
                start()
            }
        try {
            val stateDir = StateDir.resolve(state.toString())
            val http = OkHttpClient()
            val deadline = System.currentTimeMillis() + 30_000L
            var port: Int? = null
            while (port == null && System.currentTimeMillis() < deadline) {
                val control = stateDir.readControlPort()
                if (control != null) {
                    val list =
                        runCatching {
                            http
                                .newCall(Request.Builder().url("http://127.0.0.1:$control/list").build())
                                .execute()
                                .use { it.body!!.string() }
                        }.getOrDefault("")
                    port =
                        Regex(
                            "\"name\":\"alpha\",\"kind\":\"strategy\",\"port\":(\\d+)",
                        ).find(list)?.groupValues?.get(1)?.toInt()
                }
                if (port == null) Thread.sleep(100)
            }
            assertThat(port).describedAs("alpha's port from /list").isNotNull()

            fun call(
                path: String,
                post: Boolean = false,
            ) = http
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:$port$path")
                        .apply { if (post) post(ByteArray(0).toRequestBody(null)) }
                        .build(),
                ).execute()
                .use { it.code }

            assertThat(call("/health")).isEqualTo(200)
            assertThat(call("/latency")).isEqualTo(200)
            assertThat(call("/stop", post = true)).isEqualTo(202)
        } finally {
            daemon.interrupt()
            daemon.join(5_000)
        }
    }
}
