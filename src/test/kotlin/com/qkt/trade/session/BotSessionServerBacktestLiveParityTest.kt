package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.trade.BotIntent
import com.qkt.trade.BotTif
import com.qkt.trade.renderBotStrategy
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Parity at the verb surface the qkt CLI and external ML clients actually use: two
 * [BotSessionServer]s — one over a backtest session (ReplayBotRunBackend), one over a
 * running paper [com.qkt.app.LiveSession] (LiveBotRunBackend) — driven by the same
 * HTTP client script (/next, /intent with rendered bot DSL, /finish) over identical
 * ticks must produce the identical trade tape.
 *
 * The live feed is gated tick-by-tick and released in lockstep with the HTTP client's
 * own decision loop (see [runLiveServer]), so intents land between the same two ticks
 * in both modes (the spec's one-bar timing freedom is deliberately removed — under
 * this pacing any divergence is an engine bug, not skew).
 */
class BotSessionServerBacktestLiveParityTest {
    // "/intent" compiles the rendered source through the DSL parser, which requires
    // BROKER:SYMBOL market-source syntax (renderBotStrategy emits `x = $qktSymbol
    // EVERY 1m`) — unlike the engine-level parity test, which submits Signal
    // objects directly and never touches the DSL, so a bare symbol works there.
    private val symbol = "EXNESS:XAUUSD"
    private val client = HttpClient.newHttpClient()

    private fun ticks(): List<Tick> =
        (0 until 9).flatMap { bar ->
            val base = bar * 60_000L
            listOf(
                Tick(symbol, BigDecimal(2400 + bar), base + 1_000L),
                Tick(symbol, BigDecimal(2402 + bar), base + 30_000L),
            )
        } + Tick(symbol, BigDecimal("2413"), 9 * 60_000L + 1_000L)

    /** Decision script keyed by closed-bar count — identical for both arms. */
    private fun decisions(): Map<Int, Side> = mapOf(3 to Side.BUY, 6 to Side.SELL)

    private class GatedTickFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val permits = Semaphore(0)
        private val idx = AtomicInteger(0)

        fun release() = permits.release()

        fun releaseRemaining() = permits.release(ticks.size + 1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i >= ticks.size) return null
            permits.acquire()
            return ticks[i]
        }

        override fun close() = Unit
    }

    private class GatedSource(
        private val feed: GatedTickFeed,
    ) : MarketSource {
        override val name: String = "BotSessionServerParityFake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed
    }

    private fun <T> withQuietLogs(block: () -> T): T {
        val names =
            listOf(
                "com.qkt.app.LiveSession",
                "com.qkt.execution.OrderManager",
                "com.qkt.app.TradingPipeline",
                "com.qkt.risk.RiskEngine",
            )
        val loggers = names.map { LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger }
        val previous = loggers.map { it.level }
        loggers.forEach { it.level = ch.qos.logback.classic.Level.ERROR }
        try {
            return block()
        } finally {
            loggers.zip(previous).forEach { (logger, level) -> logger.level = level }
        }
    }

    private fun call(
        server: BotSessionServer,
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path"))
                .header("Authorization", "Bearer secret")
        if (method == "POST") {
            builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun intentSource(side: Side): String =
        renderBotStrategy(
            BotIntent(
                side = side,
                qktSymbol = symbol,
                lots = BigDecimal.ONE,
                sizingDsl = null,
                limitPrice = null,
                stopPrice = null,
                stopLimitPrice = null,
                sl = null,
                tp = null,
                tif = BotTif.GTC,
                expiresAtMs = null,
            ),
        ).replace("\n", "\\n")

    private fun postIntent(
        server: BotSessionServer,
        side: Side,
    ) {
        val response =
            call(
                server,
                "POST",
                "/intent",
                """{"identity":"brain","source":"${intentSource(side)}"}""",
            )
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"queued\":true")
    }

    private data class TradeKey(
        val symbol: String,
        val side: String,
        val quantity: BigDecimal,
        val price: BigDecimal,
        val timestamp: Long,
    )

    private fun Trade.key(): TradeKey =
        TradeKey(
            symbol = symbol,
            side = side.toString(),
            quantity = quantity,
            price = price,
            timestamp = timestamp,
        )

    /** Backtest arm: the CLI's `bot session start` shape, driven over HTTP. */
    private fun runBacktestServer(): List<TradeKey> {
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridge = BotBridgeStrategy()
        val engine =
            Backtest(
                strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                ticks = ticks(),
                candleWindow = TimeWindow.parse("1m"),
                initialTimestamp = ticks().first().timestamp,
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        val session =
            BotRunSession(
                runId = "parity-http-backtest",
                backend = ReplayBotRunBackend(engine),
                bridges = mapOf("brain" to bridge),
                history = history,
                recorder = recorder,
            )
        var result: BacktestResult? = null
        BotSessionServer(
            session = session,
            token = "secret",
            accountCurrency = "USD",
            onFinish = { r ->
                result = r
                null
            },
        ).use { server ->
            server.start()
            var closedBars = 0
            while (true) {
                val bar = call(server, "POST", "/next", """{"symbol":"$symbol"}""")
                if (!bar.body().contains("\"type\":\"bar\"")) break
                closedBars++
                decisions()[closedBars]?.let { postIntent(server, it) }
            }
            assertThat(call(server, "POST", "/finish").body()).contains("\"finished\":true")
        }
        return (result ?: error("backtest session must yield a result")).trades.map { it.trade.key() }
    }

    /** Live arm: the CLI's `bot session start-live` shape on a paper broker, same verbs. */
    private fun runLiveServer(): List<TradeKey> =
        withQuietLogs {
            val tickSeq = ticks()
            val history = BarHistory(capacity = 100)
            val recorder = BotSessionRecorder(history)
            val bridge = BotBridgeStrategy()
            val feed = GatedTickFeed(tickSeq)
            val liveTrades = mutableListOf<Trade>()
            val handle =
                com.qkt.app
                    .LiveSession(
                        strategies = listOf("brain" to bridge, BotSessionRecorder.ID to recorder),
                        source = GatedSource(feed),
                        symbols = listOf(symbol),
                        candleWindow = TimeWindow.parse("1m"),
                        clock = FixedClock(time = tickSeq.first().timestamp),
                        onTrade = { trade, _, _ -> liveTrades.add(trade) },
                    ).start()
            // Readiness is signalled by the backend from inside awaitNextBar, i.e. after the
            // server has received /next and captured `before` — no settle sleep needed (#1078).
            val readyForBar = AtomicInteger(0)
            val session =
                BotRunSession(
                    runId = "parity-http-live",
                    backend =
                        LiveBotRunBackend(
                            handle = handle,
                            identities = setOf("brain"),
                            clock = FixedClock(time = tickSeq.first().timestamp),
                            pollMs = 1L,
                            onAwaitingBar = { _, _ -> readyForBar.incrementAndGet() },
                        ),
                    bridges = mapOf("brain" to bridge),
                    history = history,
                    recorder = recorder,
                )
            BotSessionServer(
                session = session,
                token = "secret",
                accountCurrency = "USD",
                onFinish = { null },
            ).use { server ->
                server.start()

                // See BotSessionBacktestLiveParityTest.runLiveSession: /next resolves
                // `before = history.countFor(symbol)` fresh at call time, so the client
                // must call it BEFORE the ticks that close that bar are released. A
                // decision thread drives /next + /intent exactly as a real HTTP client
                // would; the test thread pumps ticks in lockstep via the same
                // readyForBar/decided handshake.
                val lastBar = 9
                val decided = AtomicInteger(0)
                val decisionThread =
                    Thread {
                        for (bar in 1..lastBar) {
                            val barResponse = call(server, "POST", "/next", """{"symbol":"$symbol"}""")
                            check(barResponse.body().contains("\"type\":\"bar\"")) { "bar $bar should be available" }
                            decisions()[bar]?.let { postIntent(server, it) }
                            decided.set(bar)
                        }
                    }
                decisionThread.start()

                // ticks needed (cumulative) for bar N's candle to close: the two ticks
                // inside bar N plus the first tick of bar N+1 (or, for the last bar,
                // the extra trailing tick ticks() appends instead of a bar-N+1 tick).
                val closesAfterTicks = (1..lastBar).map { bar -> if (bar < lastBar) 2 * bar + 1 else tickSeq.size }
                val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                var released = 0
                for (bar in 1..lastBar) {
                    while (readyForBar.get() < bar) {
                        check(System.nanoTime() < deadline) { "decision thread never became ready for bar $bar" }
                        Thread.sleep(1)
                    }
                    val target = closesAfterTicks[bar - 1]
                    while (released < target) {
                        feed.release()
                        released++
                    }
                    while (decided.get() < bar) {
                        check(System.nanoTime() < deadline) { "decision thread never finished bar $bar" }
                        Thread.sleep(1)
                    }
                }
                decisionThread.join(Duration.ofSeconds(10).toMillis())
                check(!decisionThread.isAlive) { "decision thread did not finish" }

                feed.releaseRemaining()
                assertThat(call(server, "POST", "/finish").body()).contains("\"finished\":true")
            }
            check(handle.awaitTermination(Duration.ofSeconds(10))) { "live session did not terminate" }
            liveTrades.map { it.key() }
        }

    @Test
    fun `http-driven bot session trades are identical in backtest and live-paper mode`() {
        val backtest = runBacktestServer()
        val live = runLiveServer()

        assertThat(backtest).isNotEmpty()
        assertThat(live).hasSameSizeAs(backtest)
        live.zip(backtest).forEach { (l, b) ->
            assertThat(l.symbol).isEqualTo(b.symbol)
            assertThat(l.side).isEqualTo(b.side)
            assertThat(l.quantity).isEqualByComparingTo(b.quantity)
            assertThat(l.price).isEqualByComparingTo(b.price)
            assertThat(l.timestamp).isEqualTo(b.timestamp)
        }
    }
}
