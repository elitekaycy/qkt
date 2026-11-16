package com.qkt.cli

import com.qkt.backtest.MetricsWindow
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `--metrics-window` / `--oos-split` parsing edges, and per-strategy risk caps refusing out-of-range values. */
class MetricsWindowsAndStrategyRiskTest {
    private val from = Instant.parse("2024-01-01T00:00:00Z")
    private val to = Instant.parse("2024-01-11T00:00:00Z")

    private fun windows(vararg argv: String) =
        BacktestMetricsWindows.parse(Args(arrayOf("backtest", "s.qkt", *argv)), from, to)

    @Test
    fun `an oos split at a date cuts the run there and the last window ends past the run's end`() {
        assertThat(windows("--oos-split", "2024-01-09")).containsExactly(
            MetricsWindow("in_sample", from.toEpochMilli(), Instant.parse("2024-01-09T00:00:00Z").toEpochMilli()),
            MetricsWindow("out_of_sample", Instant.parse("2024-01-09T00:00:00Z").toEpochMilli(), to.toEpochMilli() + 1),
        )
    }

    @Test
    fun `an oos fraction must be strictly between zero and one`() {
        for (raw in listOf("0", "1", "1.5", "-0.2")) {
            assertThatThrownBy { windows("--oos-split", raw) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("--oos-split fraction must be in (0, 1): $raw")
        }
    }

    @Test
    fun `an oos split on or outside the run's edges is refused`() {
        for (raw in listOf("2024-01-01", "2024-01-11", "2025-01-01T00:00:00")) {
            assertThatThrownBy { windows("--oos-split", raw) }
                .hasMessage("--oos-split $raw must fall strictly inside the run's --from/--to")
        }
    }

    @Test
    fun `window names must be unique, including against the split's names`() {
        assertThatThrownBy {
            windows("--metrics-window", "in_sample=2024-01-02..2024-01-03", "--oos-split", "0.5")
        }.hasMessage("metrics window names must be unique: [in_sample, in_sample, out_of_sample]")
    }

    @Test
    fun `a window spec without a name or both ends is refused`() {
        for (spec in listOf("=2024-01-02..2024-01-03", "q1=..2024-01-03", "q1=2024-01-02..", "q1 2024-01-02")) {
            assertThatThrownBy { windows("--metrics-window", spec) }
                .hasMessage("bad --metrics-window '$spec'; expected NAME=FROM..TO")
        }
    }

    @Test
    fun `per-strategy drawdown caps must be fractions in zero to one`() {
        for (bad in listOf("0", "-0.1", "1.5")) {
            assertThatThrownBy { PerStrategyRisk(maxDrawdownPct = BigDecimal(bad)) }
                .hasMessage("PerStrategyRisk.maxDrawdownPct must be in (0, 1]: $bad")
            assertThatThrownBy { PerStrategyRisk(maxDailyDrawdownPct = BigDecimal(bad)) }
                .hasMessage("PerStrategyRisk.maxDailyDrawdownPct must be in (0, 1]: $bad")
        }
        val full = PerStrategyRisk(maxDrawdownPct = BigDecimal.ONE, maxDailyDrawdownPct = BigDecimal("0.05"))
        assertThat(full.toLimits().maxDrawdownPct).isEqualByComparingTo("1")
        assertThat(full.toLimits().maxDailyDrawdownPct).isEqualByComparingTo("0.05")
    }

    @Test
    fun `per-strategy counts and cooldowns must be positive`() {
        assertThatThrownBy { PerStrategyRisk(maxTradesPerDay = 0) }
            .hasMessage("PerStrategyRisk.maxTradesPerDay must be > 0: 0")
        assertThatThrownBy { PerStrategyRisk(cooldownAfterLossMs = 0L) }
            .hasMessage("PerStrategyRisk.cooldownAfterLossMs must be > 0: 0")
        assertThatThrownBy { PerStrategyRisk(cooldownAfterLossAfterConsecutive = 0) }
            .hasMessage("PerStrategyRisk.cooldownAfterLossAfterConsecutive must be > 0: 0")
        assertThatThrownBy { PerStrategyRisk(lossStreakHalt = -1) }
            .hasMessage("PerStrategyRisk.lossStreakHalt must be > 0: -1")
        assertThatThrownBy { PerStrategyRisk(maxOpenPositions = 0) }
            .hasMessage("PerStrategyRisk.maxOpenPositions must be > 0: 0")
    }
}
