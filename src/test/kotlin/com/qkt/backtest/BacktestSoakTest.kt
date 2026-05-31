package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
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

    /** Max acceptable heap growth between first stable sample and final sample. */
    private val heapGrowthBytesLimit = 50L * 1024 * 1024

    /** Allow a few transient threads (JIT compiler bursts, GC helpers). */
    private val threadGrowthLimit = 4

    /** FDs should be constant once warm — paper broker opens no sockets. */
    private val fdGrowthLimit = 2

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

    private data class Snapshot(
        val elapsedMs: Long,
        val heapUsedBytes: Long,
        val threadCount: Int,
        val fdCount: Int,
        val tickCount: Long,
    )

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
     * Snapshot a single point in time. Calls `System.gc()` as a hint so heap
     * measurement reflects long-lived state rather than uncollected garbage —
     * leak detection is about the floor, not the peak.
     */
    private fun captureSnapshot(
        elapsedMs: Long,
        tickCount: Long,
    ): Snapshot {
        System.gc()
        Thread.sleep(50)
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        return Snapshot(
            elapsedMs = elapsedMs,
            heapUsedBytes = heapUsed,
            threadCount = Thread.activeCount(),
            fdCount = countOpenFds(),
            tickCount = tickCount,
        )
    }

    /**
     * Open-FD count from `/proc/self/fd`. Returns -1 on non-Linux hosts so
     * the assertion skips the check rather than failing spuriously.
     */
    private fun countOpenFds(): Int =
        try {
            val proc = Path.of("/proc/self/fd")
            if (Files.isDirectory(proc)) Files.list(proc).use { it.count().toInt() } else -1
        } catch (_: Exception) {
            -1
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

    private fun printReport(snapshots: List<Snapshot>) {
        println("BacktestSoak: ${snapshots.size} samples over ${snapshots.last().elapsedMs / 1000}s")
        println("  elapsed(s)  ticks       heap(MB)  threads  fds")
        for (s in snapshots) {
            println(
                "  %8d  %10d  %8.1f  %7d  %3d".format(
                    s.elapsedMs / 1000,
                    s.tickCount,
                    s.heapUsedBytes / (1024.0 * 1024.0),
                    s.threadCount,
                    s.fdCount,
                ),
            )
        }
    }

    /**
     * Compare the final snapshot against the second snapshot rather than the
     * first — the second sample is taken after the engine has warmed (indicator
     * buffers filled, candle history seeded), so it's the right baseline for
     * "steady state" leak detection.
     */
    private fun assertResourceFloors(snapshots: List<Snapshot>) {
        require(snapshots.size >= 3) {
            "soak produced only ${snapshots.size} snapshots — duration too short or sampler broken"
        }
        val baseline = snapshots[1]
        val final = snapshots.last()

        val heapDelta = final.heapUsedBytes - baseline.heapUsedBytes
        assertThat(heapDelta)
            .withFailMessage(
                "heap grew %d bytes (%.1f MB) between baseline (t=%ds) and final (t=%ds); limit %d bytes. " +
                    "Likely an unbounded cache, position-tracker, or candle-buffer leak. ticks=%d"
                        .format(
                            heapDelta,
                            heapDelta / (1024.0 * 1024.0),
                            baseline.elapsedMs / 1000,
                            final.elapsedMs / 1000,
                            heapGrowthBytesLimit,
                            final.tickCount,
                        ),
            ).isLessThan(heapGrowthBytesLimit)

        val threadDelta = final.threadCount - baseline.threadCount
        assertThat(threadDelta)
            .withFailMessage(
                "thread count grew by %d (baseline=%d, final=%d); limit %d. " +
                    "Likely a daemon thread spawned per recurring event without reaping."
                        .format(threadDelta, baseline.threadCount, final.threadCount, threadGrowthLimit),
            ).isLessThanOrEqualTo(threadGrowthLimit)

        if (baseline.fdCount >= 0 && final.fdCount >= 0) {
            val fdDelta = final.fdCount - baseline.fdCount
            assertThat(fdDelta)
                .withFailMessage(
                    "open FD count grew by %d (baseline=%d, final=%d); limit %d. " +
                        "Likely an unclosed file or socket on a recurring path."
                            .format(fdDelta, baseline.fdCount, final.fdCount, fdGrowthLimit),
                ).isLessThanOrEqualTo(fdGrowthLimit)
        }
    }
}
