package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.sweep.SweepResult
import com.qkt.backtest.sweep.SweepRun
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepReportWriterShapesTest {
    private val zero = BigDecimal.ZERO
    private val report =
        PerformanceReport(
            realizedTotal = BigDecimal("12"),
            unrealizedTotal = zero,
            totalPnL = BigDecimal("12"),
            tradeCount = 2,
            winRate = BigDecimal("0.5"),
            maxDrawdown = BigDecimal("0.01"),
            profitFactor = BigDecimal("2.5"),
            avgWin = zero,
            avgLoss = zero,
            largestWin = zero,
            largestLoss = zero,
            maxConsecutiveLosses = 1,
            sharpeRatio = BigDecimal("1.25"),
            calmarRatio = BigDecimal("3.5"),
            equityCurve = emptyList(),
        )

    private fun result(global: PerformanceReport) =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = global,
            perStrategy = emptyMap(),
            cadence = SampleCadence.FILL,
        )

    @Test
    fun `ratios are written when present and left empty when not`(
        @TempDir dir: Path,
    ) {
        val noRatios = report.copy(sharpeRatio = null, profitFactor = null, calmarRatio = null)
        val sweep = SweepResult(listOf(SweepRun("a", 1, result(report)), SweepRun("b", 2, result(noRatios))))

        SweepReportWriter(dir).write(sweep)

        val csv = Files.readAllLines(dir.resolve("sweep_summary.csv"))
        assertThat(csv[1]).isEqualTo("a,1,12,1.25,0.01,0.5,2,2.5,3.5")
        assertThat(csv[2]).isEqualTo("b,2,12,,0.01,0.5,2,,")
        val json = Files.readString(dir.resolve("sweep_summary.json"))
        assertThat(json).contains("\"sharpeRatio\": \"1.25\"", "\"calmarRatio\": null")
    }

    @Test
    fun `an empty sweep writes an empty json array`(
        @TempDir dir: Path,
    ) {
        SweepReportWriter(dir).write(SweepResult<Int>(emptyList()))

        assertThat(Files.readString(dir.resolve("sweep_summary.json"))).isEqualTo("[]")
    }

    @Test
    fun `a missing directory and configs with quotes or newlines are refused`(
        @TempDir dir: Path,
    ) {
        val missing = dir.resolve("absent")
        assertThatThrownBy { SweepReportWriter(missing).write(SweepResult<Int>(emptyList())) }
            .hasMessage("Not a directory: $missing")
        assertThatThrownBy {
            SweepReportWriter(
                dir,
            ).write(SweepResult(listOf(SweepRun("q", "say \"hi\"", result(report)))))
        }.hasMessageContaining("must not contain comma, double-quote, or newline")
        assertThatThrownBy { SweepReportWriter(dir).write(SweepResult(listOf(SweepRun("n", "a\nb", result(report))))) }
            .hasMessageContaining("must not contain comma, double-quote, or newline")
    }
}
