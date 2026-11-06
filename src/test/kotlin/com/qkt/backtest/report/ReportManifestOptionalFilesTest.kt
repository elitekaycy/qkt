package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BookRiskReport
import com.qkt.backtest.BookRiskSample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.metrics.MonteCarlo
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReportManifestOptionalFilesTest {
    private val zero = BigDecimal.ZERO

    @Test
    fun `book risk and monte carlo files are hashed, and a run without evidence has no provenance`(
        @TempDir dir: Path,
    ) {
        val global =
            PerformanceReport(
                realizedTotal = zero,
                unrealizedTotal = zero,
                totalPnL = zero,
                tradeCount = 0,
                winRate = zero,
                maxDrawdown = zero,
                profitFactor = null,
                avgWin = zero,
                avgLoss = zero,
                largestWin = zero,
                largestLoss = zero,
                maxConsecutiveLosses = 0,
                sharpeRatio = null,
                calmarRatio = null,
                equityCurve = emptyList(),
                monteCarlo = MonteCarlo.run(listOf(BigDecimal("5"), BigDecimal("-3")), BigDecimal("100"), 10, 1L),
            )
        val bookRisk =
            BookRiskReport(
                series = listOf(BookRiskSample(0L, BigDecimal("10"), BigDecimal("5"), BigDecimal("100"))),
                bookVol = null,
                maxGrossExposure = BigDecimal("10"),
                maxNetExposure = BigDecimal("5"),
            )
        val result =
            BacktestResult(
                trades = emptyList(),
                rejections = emptyList(),
                finalPositions = emptyMap(),
                global = global,
                perStrategy = emptyMap(),
                cadence = SampleCadence.FILL,
                bookRisk = bookRisk,
            )

        BacktestReportWriter(dir).write(result)

        val manifest = Json.parseToJsonElement(Files.readString(dir.resolve("manifest.json"))).jsonObject
        val paths =
            manifest
                .getValue(
                    "artifacts",
                ).jsonArray
                .map {
                    it.jsonObject
                        .getValue("path")
                        .jsonPrimitive.content
                }
        assertThat(paths).contains("book_risk.csv", "monte_carlo_fan.csv")
        assertThat(manifest.getValue("generatedAt")).isEqualTo(JsonNull)
        assertThat(manifest.getValue("gitSha")).isEqualTo(JsonNull)
    }
}
