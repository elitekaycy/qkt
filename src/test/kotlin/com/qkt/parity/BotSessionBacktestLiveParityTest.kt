package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotBridgeStrategy
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.LiveBotRunBackend
import com.qkt.trade.session.ReplayBotRunBackend
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * The gating parity claim for external (ML/agent) strategies: a bot run session in
 * BACKTEST mode (ReplayBotRunBackend over a paced ReplayEngine) and one in LIVE mode
 * (LiveBotRunBackend over a running LiveSession on a paper broker) produce the exact
 * same trade tape when the identical client script makes the identical decisions on
 * the identical ticks.
 *
 * The spec (2026-08-19-bot-run-sessions-design.md §9) allows intent-timing skew of up
 * to one bar for live sessions because live intents land at wall-clock arrival. This
 * test removes that freedom deterministically: the live feed is gated tick-by-tick and
 * released in lockstep with the client's own decision loop (see [runLiveSession]), so
 * every submit lands between the same two ticks as in the backtest arm. Under that
 * pacing the two modes must be byte-identical; any divergence is an engine bug, not
 * timing skew.
 */
class BotSessionBacktestLiveParityTest {
    private val symbol = "XAUUSD"

    /** 9 one-minute bars, two ticks each, plus a final tick that closes bar 9. */
    private fun ticks(): List<Tick> =
        (0 until 9).flatMap { bar ->
            val base = bar * 60_000L
            listOf(
                Tick(symbol, BigDecimal(2400 + bar), base + 1_000L),
                Tick(symbol, BigDecimal(2402 + bar), base + 30_000L),
            )
        } + Tick(symbol, BigDecimal("2413"), 9 * 60_000L + 1_000L)

    /** The client's decision script, keyed by closed-bar count: identical in both arms. */
    private fun decisions(): Map<Int, Signal> =
        mapOf(
            3 to Signal.Buy(symbol, BigDecimal.ONE),
            6 to Signal.Sell(symbol, BigDecimal.ONE),
        )

    /** Feed that delivers one tick per released permit, so the test paces the live session. */
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
        override val name: String = "BotSessionParityFake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed
    }

    /** Same quiet-logs discipline as BacktestLiveParityTest (test log budget). */
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

    private data class SessionTrade(
        val symbol: String,
        val side: String,
        val quantity: BigDecimal,
        val price: BigDecimal,
        val timestamp: Long,
    )

    private fun Trade.key(): SessionTrade =
        SessionTrade(
            symbol = symbol,
            side = side.toString(),
            quantity = quantity,
            price = price,
            timestamp = timestamp,
        )

    /** Backtest arm: replay backend; each next() advances the engine one closed bar. */
    private fun runBacktestSession(): List<SessionTrade> {
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
                runId = "parity-backtest",
                backend = ReplayBotRunBackend(engine),
                bridges = mapOf("brain" to bridge),
                history = history,
                recorder = recorder,
            )
        var closedBars = 0
        while (session.next(symbol) != null) {
            closedBars++
            decisions()[closedBars]?.let { session.submit("brain", it) }
        }
        val result = session.finish() ?: error("backtest session must yield a result")
        return result.trades.map { it.trade.key() }
    }

    /**
     * Live arm: real LiveSession on the paper broker, fed tick-by-tick through the gate.
     *
     * [BotRunSession.next] captures `before = history.countFor(symbol)` fresh at the
     * moment it is CALLED — it has no memory of bars already served. So the client
     * must call `next()` for bar N *before* the ticks that close bar N are released
     * (in backtest mode `next()` drives the replay itself, so this is automatic; in
     * live mode ticks arrive from an independent feed, so the test must arrange it).
     * A decision thread drives `next()`/`submit()` exactly as a real external client
     * would; the test thread pumps ticks, releasing bar N's closing tick only after
     * the decision thread has announced ([readyForBar]) that it is about to wait for
     * bar N, and waiting for that bar's decision to finish ([decided]) before moving
     * on — one bar fully in lockstep at a time, so intent timing matches the backtest
     * arm exactly rather than relying on the spec's allowed one-bar skew.
     */
    private fun runLiveSession(): List<SessionTrade> =
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
            val session =
                BotRunSession(
                    runId = "parity-live",
                    backend = LiveBotRunBackend(handle = handle, identities = setOf("brain"), pollMs = 1L),
                    bridges = mapOf("brain" to bridge),
                    history = history,
                    recorder = recorder,
                )

            val lastBar = 9
            val readyForBar = AtomicInteger(0)
            val decided = AtomicInteger(0)
            val decisionThread =
                Thread {
                    for (bar in 1..lastBar) {
                        readyForBar.set(bar)
                        checkNotNull(session.next(symbol)) { "bar $bar should be available" }
                        decisions()[bar]?.let { session.submit("brain", it) }
                        decided.set(bar)
                    }
                }
            decisionThread.start()

            // ticks needed (cumulative) for bar N's candle to close: the two ticks
            // inside bar N plus the first tick of bar N+1 (or, for the last bar, the
            // extra trailing tick ticks() appends instead of a bar-N+1 tick).
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
            session.finish()
            check(handle.awaitTermination(Duration.ofSeconds(10))) { "live session did not terminate" }
            liveTrades.map { it.key() }
        }

    @Test
    fun `bot session trades are identical in backtest mode and live-paper mode`() {
        val backtest = runBacktestSession()
        val live = runLiveSession()

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
