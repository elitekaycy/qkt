package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * #65 — throughput stress test for the Backtest tick replay path.
 *
 * Generates a deterministic 1M-tick stream against a representative strategy
 * (two indicators + one cross-stream rule) and asserts the engine sustains
 * a minimum throughput. The whole point is to catch regressions:
 *  - new logic in the hot path that costs more than expected per tick
 *  - allocation creep that triggers more frequent GC pauses
 *  - accidental quadratic loops in indicator chains
 *
 * Tagged `stress` so it stays out of default CI per the existing convention
 * (excluded in `build.gradle.kts`). Run via:
 *
 *   ./gradlew test -PincludeTags=stress --tests 'com.qkt.backtest.BacktestThroughputStressTest'
 *
 * Tuning notes:
 *  - `tickCount`: bump to 10M for soak-style runs. 1M default keeps test under
 *    the wall-clock ceiling on developer hardware.
 *  - `maxWallClockSec`: a generous catastrophic-regression guard, not a precise
 *    throughput floor. The timed region is the whole `Backtest.run()` call, which
 *    includes post-replay MonteCarlo/drawdown metrics that scale with trade count —
 *    so a ticks/s floor would measure the wrong thing. The printed end-to-end ticks/s
 *    is for observability only. What matters: a regression of order/tick processing
 *    back to super-linear cost would blow far past this ceiling.
 *  - The strategy must be representative — a no-op LOG would over-report
 *    throughput because indicator updates are the typical bottleneck.
 */
@Tag("stress")
class BacktestThroughputStressTest {
    private val symbol = "BACKTEST:BTCUSDT"
    private val candleWindow = TimeWindow.ONE_MINUTE
    private val tickCount = 1_000_000

    // Generous wall-clock ceiling, not a precise throughput floor: the timed region is the whole
    // Backtest.run() (tick replay PLUS post-run MonteCarlo/drawdown metrics that scale with trade
    // count), so a ticks/s floor would measure the wrong thing. This guards what matters — a
    // regression of order processing back to super-linear cost blows far past this ceiling.
    private val maxWallClockSec = 180.0
    private val seed = 0xABCDEF01L

    /**
     * EMA + RSI on the same stream, one cross-indicator rule. Exercises the
     * indicator update path, the snapshot store, and the rule fire path on every
     * candle close.
     */
    private val strategySrc =
        """
        STRATEGY stress VERSION 1
        SYMBOLS
          x = $symbol EVERY 1m
        RULES
          WHEN ema(x.close, 9) > ema(x.close, 21) AND rsi(x.close, 14) < 70
          THEN BUY x SIZING 0.01
        """.trimIndent()

    /**
     * Deterministic random-walk tick stream. Seed-driven so a failure
     * reproduces with no extra state.
     */
    private fun generateTicks(): List<Tick> {
        val random = java.util.Random(seed)
        val ticks = ArrayList<Tick>(tickCount)
        // Walk in Double, not BigDecimal: a BigDecimal walk grows its scale a few digits every
        // tick (each multiply accumulates precision), so generating a million ticks slows to a
        // crawl. Double is O(1) and plenty for a synthetic price.
        var price = 50_000.0
        val tickInterval = 60_000L / 10L // 10 ticks per minute → 1 candle per 60_000ms
        for (i in 0 until tickCount) {
            // Walk price by up to ±0.05% per tick.
            val deltaBps = (random.nextInt(11) - 5) // -5..5
            price += price * deltaBps / 10_000.0
            if (price <= 0) price = 50_000.0
            ticks.add(
                Tick(
                    symbol = symbol,
                    price = Money.of(String.format(java.util.Locale.ROOT, "%.2f", price)),
                    timestamp = i * tickInterval,
                ),
            )
        }
        return ticks
    }

    @Test
    fun `engine sustains minimum tick throughput on representative strategy`() {
        val ticks = generateTicks()
        val strategy =
            AstCompiler().compile((Dsl.parse(strategySrc) as ParseResult.Success).value)

        val startNs = System.nanoTime()
        val result =
            Backtest(
                strategies = listOf("stress" to strategy),
                ticks = ticks,
                candleWindow = candleWindow,
            ).run()
        val elapsedNs = System.nanoTime() - startNs

        val elapsedSec = elapsedNs / 1_000_000_000.0
        val ticksPerSec = (tickCount / elapsedSec).toLong()

        // Print to stdout so a soak run captures the number even when the assertion passes.
        println(
            "BacktestThroughputStress: $tickCount ticks in %.2fs → %d ticks/s end-to-end (incl. metrics); ceiling %.0fs"
                .format(elapsedSec, ticksPerSec, maxWallClockSec),
        )

        assertThat(elapsedSec)
            .withFailMessage(
                "1M-tick backtest took %.1fs (> %.0fs ceiling) — likely a regression of order/tick processing to super-linear cost. end-to-end ticks/s=%d, trades=%d"
                    .format(elapsedSec, maxWallClockSec, ticksPerSec, result.trades.size),
            ).isLessThan(maxWallClockSec)
    }
}
