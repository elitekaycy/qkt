package com.qkt.cli.daemon.portfolio

import com.qkt.cli.daemon.StateDir
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A deployed portfolio's child answers on its own port, and a book with nothing to enforce builds no aggregator. */
class PortfolioChildServerTest {
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
    fun `a conditional book without limits reads its symbols and each child serves health latency and stop`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(
            tmp.resolve("child.qkt"),
            "STRATEGY child_s VERSION 1\nSYMBOLS\n    x = BACKTEST:X EVERY 1m\nRULES\n    WHEN x.close > 0 THEN BUY x SIZING 1\n",
        )
        val book = tmp.resolve("book.qkt")
        Files.writeString(
            book,
            "PORTFOLIO cond_book VERSION 1\nSYMBOLS\n    x = BACKTEST:X EVERY 1m\nIMPORT 'child.qkt' AS child\n" +
                "RULES\n    WHEN x.close > 0 RUN child WEIGHT 1.0\n",
        )
        val requested = CopyOnWriteArrayList<List<String>>()
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { symbols ->
                    requested.add(symbols)
                    IdleSource()
                },
                maxDailyLoss = BigDecimal.ZERO,
            )

        val record = deployer.deploy("cond_book", PortfolioLoader.load(book))
        try {
            assertThat(requested).contains(listOf("BACKTEST:X"))
            val child = record.children.single()
            val http = OkHttpClient()

            fun call(
                path: String,
                post: Boolean = false,
            ) = http
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${child.port}$path")
                        .apply { if (post) post(ByteArray(0).toRequestBody(null)) }
                        .build(),
                ).execute()
                .use { it.code }

            assertThat(call("/health")).isEqualTo(200)
            assertThat(call("/latency")).isEqualTo(200)
            assertThat(call("/stop", post = true)).isEqualTo(202)
            assertThat(child.live.awaitTermination(Duration.ofSeconds(20))).isTrue()
            assertThat(child.isRunning()).isFalse()
        } finally {
            record.supervisor.stop()
            for (deployedChild in record.children) runCatching { deployedChild.close() }
        }
    }
}
