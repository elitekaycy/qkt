package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
class BotSessionServerBacktestLiveParityTest : BotSessionParityFixture() {
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
