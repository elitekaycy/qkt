package com.qkt.parity

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.parity.BotSessionParityScript.SessionTrade
import com.qkt.parity.BotSessionParityScript.decisions
import com.qkt.parity.BotSessionParityScript.key
import com.qkt.parity.BotSessionParityScript.symbol
import com.qkt.parity.BotSessionParityScript.ticks
import com.qkt.trade.session.BarHistory
import com.qkt.trade.session.BotBridgeStrategy
import com.qkt.trade.session.BotRunSession
import com.qkt.trade.session.BotSessionRecorder
import com.qkt.trade.session.LiveBotRunBackend
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/** Runs the bot session against a live paper session whose feed is released in lockstep with the client. */
internal object BotSessionLiveArm {
    /** Feed that delivers one tick per released permit, so the test paces the live session. */
    class GatedTickFeed(
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

    class GatedSource(
        private val feed: GatedTickFeed,
    ) : MarketSource {
        override val name: String = "BotSessionParityFake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = feed
    }

    /** Same quiet-logs discipline as BacktestLiveParityTest (test log budget). */
    fun <T> withQuietLogs(block: () -> T): T {
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
    fun runLiveSession(): List<SessionTrade> =
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
            val lastBar = 9
            // "Ready for bar N" is signalled by the backend itself, from inside awaitNextBar,
            // AFTER session.next() has captured `before`. Announcing it from the decision
            // thread before calling next() left a window where the pumped ticks could close
            // the bar before `before` was read, landing the submit one tick late (#1078).
            val readyForBar = AtomicInteger(0)
            val decided = AtomicInteger(0)
            val session =
                BotRunSession(
                    runId = "parity-live",
                    backend =
                        LiveBotRunBackend(
                            handle = handle,
                            identities = setOf("brain"),
                            pollMs = 1L,
                            onAwaitingBar = { _, _ -> readyForBar.incrementAndGet() },
                        ),
                    bridges = mapOf("brain" to bridge),
                    history = history,
                    recorder = recorder,
                )
            val decisionThread =
                Thread {
                    for (bar in 1..lastBar) {
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
}
