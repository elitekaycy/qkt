package com.qkt.cli.daemon.portfolio

import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Runs a portfolio's WHEN-clauses against live market data and toggles each
 * [ChildHandle.gateActive] flag so children trade only when their gate condition is
 * true. ALWAYS_RUN clauses set their child active once at [start]; conditional rules
 * re-evaluate on each market tick.
 *
 * Rule evaluation is delegated to [PortfolioGate], which compiles conditions with full
 * indicator binding so the same logic can be reused in backtest.
 *
 * One supervisor thread per portfolio (named `qkt-portfolio-supervisor-<name>`),
 * daemonized so the JVM can exit when the main daemon stops. Idempotent
 * [start] / [stop] — extra calls are no-ops.
 *
 * When [marketSource] is null (portfolios that declare no streams), only the
 * always-run rules fire; no tick loop spins up.
 */
class PortfolioSupervisor(
    val ast: PortfolioAst,
    val children: List<ChildHandle>,
    val marketSource: MarketSource?,
    private val riskAggregator: PortfolioRiskAggregator? = null,
    private val riskIntervalMs: Long = 1000L,
    gate: PortfolioGate? = null,
) {
    private val log = LoggerFactory.getLogger(PortfolioSupervisor::class.java)
    private val runFlag = AtomicBoolean(false)
    private var thread: Thread? = null
    private var riskThread: Thread? = null

    /**
     * The evaluator that owns indicator bindings and produces [PortfolioGate.GateState].
     * Injected gates are useful for tests with fixed clocks; otherwise a live gate is built.
     */
    private val gate: PortfolioGate =
        gate
            ?: PortfolioGate(ast, SystemClock(), TradingCalendar.crypto()).also { it.prepare() }

    /** True between [start] and [stop]. */
    val running: Boolean get() = runFlag.get()

    /** Spin up the supervisor thread and apply ALWAYS_RUN children. Idempotent. */
    fun start() {
        if (!runFlag.compareAndSet(false, true)) return
        applyAlwaysRunRules()
        startRiskHeartbeat()
        if (marketSource == null) return
        thread =
            Thread({
                org.slf4j.MDC.put("strategy", ast.name)
                try {
                    tickLoop()
                } finally {
                    org.slf4j.MDC.remove("strategy")
                }
            }, "qkt-portfolio-supervisor-${ast.name}").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * Evaluate the book-level drawdown aggregator on a fixed cadence, independent of market streams,
     * so always-run books (no book-level streams) are still checked. No-op when no aggregator.
     */
    private fun startRiskHeartbeat() {
        val agg = riskAggregator ?: return
        riskThread =
            Thread({
                org.slf4j.MDC.put("strategy", ast.name)
                try {
                    var consecutiveFailures = 0
                    while (runFlag.get()) {
                        try {
                            agg.evaluate()
                            consecutiveFailures = 0
                        } catch (e: Exception) {
                            consecutiveFailures++
                            log.warn(
                                "portfolio risk eval failed ({}/{}): {}",
                                consecutiveFailures,
                                RISK_FAILURE_THRESHOLD,
                                e.message,
                            )
                            if (consecutiveFailures >= RISK_FAILURE_THRESHOLD) {
                                agg.failClosed(
                                    "portfolio risk evaluation failed $consecutiveFailures consecutive times: " +
                                        "${e.message}",
                                )
                            }
                        }
                        Thread.sleep(riskIntervalMs)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    org.slf4j.MDC.remove("strategy")
                }
            }, "qkt-portfolio-risk-${ast.name}").apply {
                isDaemon = true
                start()
            }
    }

    /** Requests both supervisor loops to stop without waiting. */
    fun requestStop() {
        if (!runFlag.compareAndSet(true, false)) return
        thread?.interrupt()
        riskThread?.interrupt()
    }

    /** Waits up to [timeout] for both supervisor loops. */
    fun awaitStopped(timeout: Duration): Boolean {
        require(!timeout.isNegative) { "stop timeout must not be negative" }
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        for (candidate in listOfNotNull(thread, riskThread)) {
            val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            if (remaining > 0L) {
                candidate.join(
                    remaining / 1_000_000L,
                    (remaining % 1_000_000L).toInt(),
                )
            }
        }
        val stopped = thread?.isAlive != true && riskThread?.isAlive != true
        if (thread?.isAlive != true) thread = null
        if (riskThread?.isAlive != true) riskThread = null
        return stopped
    }

    /** Stop the supervisor loops and wait up to five seconds. Idempotent. */
    fun stop() {
        requestStop()
        awaitStopped(Duration.ofSeconds(5))
    }

    internal fun applyDesired(desired: Map<String, Boolean>) {
        for (child in children) {
            val want = desired[child.alias] ?: false
            val have = child.gateActive.get()
            if (want == have) continue
            if (want) {
                child.gateActive.set(true)
                log.info("${child.alias} activated")
            } else {
                child.gateActive.set(false)
                log.info("${child.alias} deactivated, hold=${child.hold}")
                if (!child.hold) child.flatten()
            }
        }
    }

    private fun applyAlwaysRunRules() {
        applyDesired(gate.initialState().activeByAlias)
    }

    internal fun onCandle(candle: Candle) {
        gate.onCandle(candle)
        applyDesired(gate.currentState().activeByAlias)
    }

    private fun tickLoop() {
        val source = marketSource ?: return
        val symbols = ast.streams.map { it.qktSymbol }.distinct()
        if (symbols.isEmpty()) return

        val window = TimeWindow.parse(ast.streams.first().timeframe)
        val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
        CandleAggregator(bus, window)
        bus.subscribe<CandleEvent> { e ->
            riskAggregator?.sample(e.candle.endTime)
            onCandle(e.candle)
        }

        val feed = source.liveTicks(symbols)
        try {
            while (runFlag.get()) {
                val tick = feed.next() ?: break
                bus.publish(TickEvent(tick))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { feed.close() }
        }
    }

    private companion object {
        const val RISK_FAILURE_THRESHOLD = 3
    }
}
