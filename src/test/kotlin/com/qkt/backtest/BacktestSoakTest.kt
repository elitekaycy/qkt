package com.qkt.backtest

import com.qkt.backtest.SoakResourceSampler.Snapshot
import com.qkt.backtest.SoakResourceSampler.assertResourceFloors
import com.qkt.backtest.SoakResourceSampler.captureSnapshot
import com.qkt.backtest.SoakResourceSampler.printReport
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import java.math.BigDecimal
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * #65 — soak harness for the Backtest engine.
 *
 * Runs a continuous deterministic tick stream for a wall-clock duration
 * (5 minutes by default) and samples three resource metrics at regular
 * intervals:
 *  - heap-used after a GC hint (catches slow memory leaks)
 *  - live thread count (catches thread leaks)
 *  - open file descriptors via `/proc/self/fd` (catches FD/socket leaks; Linux only)
 *
 * The throughput harness ([BacktestThroughputStressTest]) catches step-change
 * regressions in per-tick cost. Soak catches the orthogonal class of bug: slow
 * leaks that hide at steady state and OOM the daemon after days of clean uptime.
 *
 * Limitation: backtest replays ticks in a tight loop with no simulated clock
 * advancement beyond tick timestamps. Leaks tied to calendar time (anything
 * that resets daily/hourly) need a live-mode simulation with simulated wall
 * time — that's a follow-on slice under #65, not this one.
 *
 * Tagged `soak` so it stays out of default CI per the existing convention
 * (excluded in `build.gradle.kts`). Run via:
 *
 *   ./gradlew test -PincludeTags=soak --tests 'com.qkt.backtest.BacktestSoakTest'
 *
 * Bump [soakDurationMs] (e.g. to 30 min or 2 h) for deeper signal — the
 * detection floor for a leak of rate R drops linearly with run duration.
 * 5 min comfortably catches >100 KB/s leaks; 30 min catches >20 KB/s.
 */
@Tag("soak")
class BacktestSoakTest {
    private val symbol = "BACKTEST:BTCUSDT"
    private val candleWindow = TimeWindow.ONE_MINUTE
    private val soakDurationMs = 5L * 60 * 1000
    private val sampleIntervalMs = 30L * 1000
    private val seed = 0xDEADBEEFL

    /**
     * Indicator-only strategy: the rule condition cannot fire (RSI is bounded
     * to [0, 100]). This exercises the engine's per-tick plumbing — indicator
     * updates, candle aggregation, rule evaluation — without firing orders.
     *
     * Why no orders: a BUY-only rule would accumulate positions in the
     * PositionTracker linearly with trade count, which would fail the heap
     * assertion on *intended* behavior rather than on a real leak. A separate
     * soak slice with a buy/sell-balanced strategy will cover position-tracker
     * accumulation.
     */
    private val strategySrc =
        """
        STRATEGY soak VERSION 1
        SYMBOLS
          x = $symbol EVERY 1m
        RULES
          WHEN ema(x.close, 9) > ema(x.close, 21) AND rsi(x.close, 14) < 0
          THEN BUY x SIZING 0.01
        """.trimIndent()

    @Test
    fun `engine sustains resource floor over a continuous run`() {
        val strategy =
            AstCompiler().compile((Dsl.parse(strategySrc) as ParseResult.Success).value)

        val snapshots = mutableListOf<Snapshot>()
        val startMs = System.currentTimeMillis()
        val deadlineMs = startMs + soakDurationMs
        var tickCount = 0L
        var lastSampleMs = startMs

        snapshots.add(captureSnapshot(elapsedMs = 0L, tickCount = 0L))

        val ticks =
            randomWalkTicks(seed)
                .takeWhile { System.currentTimeMillis() < deadlineMs }
                .onEach {
                    tickCount++
                    val now = System.currentTimeMillis()
                    if (now - lastSampleMs >= sampleIntervalMs) {
                        snapshots.add(captureSnapshot(elapsedMs = now - startMs, tickCount = tickCount))
                        lastSampleMs = now
                    }
                }

        Backtest(
            strategies = listOf("soak" to strategy),
            feed = SequenceTickFeed(ticks),
            candleWindow = candleWindow,
        ).run()

        val final = captureSnapshot(elapsedMs = System.currentTimeMillis() - startMs, tickCount = tickCount)
        snapshots.add(final)

        printReport(snapshots)
        assertResourceFloors(snapshots)
    }

    /**
     * Deterministic random-walk ticks. Lazy and unbounded — the consumer
     * bounds the run with [Sequence.takeWhile]. ±0.05% per tick around a
     * 50_000 starting price.
     */
    private fun randomWalkTicks(seed: Long): Sequence<Tick> =
        sequence {
            val random = java.util.Random(seed)
            var price = BigDecimal("50000")
            var index = 0L
            val tickInterval = 60_000L / 10L
            while (true) {
                val deltaBps = random.nextInt(11) - 5
                price = price.add(price.multiply(BigDecimal(deltaBps).divide(BigDecimal(10_000))))
                if (price.signum() <= 0) price = BigDecimal("50000")
                yield(
                    Tick(
                        symbol = symbol,
                        price = Money.of(price.toPlainString()),
                        timestamp = index * tickInterval,
                    ),
                )
                index++
            }
        }
}
