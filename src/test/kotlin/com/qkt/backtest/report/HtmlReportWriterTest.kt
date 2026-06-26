package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.EquitySample
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.TradeRecord
import com.qkt.common.Side
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.ExecutionEvidence
import com.qkt.evidence.ExperimentEvidence
import com.qkt.evidence.PromotionEvidence
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HtmlReportWriterTest {
    @Test
    fun `writes a self-contained html with all sections`(
        @TempDir tmp: Path,
    ) {
        val report = stubReport()
        val result = stubResult(report)
        HtmlReportWriter(HtmlReportConfig()).write(result, tmp.resolve("report.html"))

        val html = Files.readString(tmp.resolve("report.html"))
        assertThat(html).startsWith("<!doctype html>")
        assertThat(html).contains("<svg")
        assertThat(html).contains("Drawdown periods")
        assertThat(html).contains("Monte Carlo")
        assertThat(html).contains("Trades")
        assertThat(html).contains("riskUsd")
        assertThat(html).contains("Run evidence")
        assertThat(html).contains("sha256:strategy")
        assertThat(html).contains("paper-fast")
        assertThat(html).contains("split.train")
        assertThat(html).contains("2026-06-04T00:00:00Z/2026-06-04T04:00:00Z")
        assertThat(html).contains("promotion rationale")
        assertThat(html).doesNotContain("<script")
    }

    private fun stubReport(): PerformanceReport =
        PerformanceReport(
            realizedTotal = BigDecimal("100"),
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal("100"),
            tradeCount = 50,
            winRate = BigDecimal("0.6"),
            maxDrawdown = BigDecimal("-0.05"),
            profitFactor = BigDecimal("1.5"),
            avgWin = BigDecimal("3"),
            avgLoss = BigDecimal("-2"),
            largestWin = BigDecimal("10"),
            largestLoss = BigDecimal("-7"),
            maxConsecutiveLosses = 3,
            sharpeRatio = BigDecimal("1.2"),
            calmarRatio = BigDecimal("0.8"),
            equityCurve =
                listOf(
                    EquitySample(0L, BigDecimal("100")),
                    EquitySample(1L, BigDecimal("110")),
                    EquitySample(2L, BigDecimal("105")),
                    EquitySample(3L, BigDecimal("115")),
                ),
            drawdownPeriods =
                listOf(
                    DrawdownPeriod(
                        peakTimestamp = 1L,
                        peakEquity = BigDecimal("110"),
                        troughTimestamp = 2L,
                        troughEquity = BigDecimal("105"),
                        recoveryTimestamp = 3L,
                        depthPct = BigDecimal("-0.045"),
                        durationMs = 2L,
                        ongoing = false,
                    ),
                ),
            monteCarlo =
                MonteCarloSummary(
                    simulations = 100,
                    finalEquityP5 = BigDecimal("80"),
                    finalEquityP25 = BigDecimal("95"),
                    finalEquityP50 = BigDecimal("110"),
                    finalEquityP75 = BigDecimal("120"),
                    finalEquityP95 = BigDecimal("140"),
                    maxDrawdownP5 = BigDecimal("-0.02"),
                    maxDrawdownP95 = BigDecimal("-0.15"),
                    probabilityNegativeFinal = BigDecimal("0.05"),
                    equityFanByTradeIndex =
                        (0..9).map {
                            EquityFanPoint(
                                tradeIndex = it,
                                p5 = BigDecimal(80 + it),
                                p25 = BigDecimal(95 + it),
                                p50 = BigDecimal(105 + it),
                                p75 = BigDecimal(115 + it),
                                p95 = BigDecimal(135 + it),
                            )
                        },
                ),
        )

    private fun stubResult(report: PerformanceReport): BacktestResult =
        BacktestResult(
            trades =
                listOf(
                    TradeRecord(
                        Trade("o-1", "BTCUSDT", BigDecimal("100"), BigDecimal("1"), Side.BUY, 0L),
                        BigDecimal("5"),
                        "test",
                        BigDecimal("10"),
                    ),
                ),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = report,
            perStrategy = mapOf("test" to report),
            cadence = SampleCadence.TICK,
            evidence =
                EvidenceEnvelope(
                    qktVersion = "test",
                    gitSha = "abc123",
                    buildTimestamp = "2026-06-25T00:00:00Z",
                    command = listOf("backtest", "s.qkt"),
                    strategyHash = "sha256:strategy",
                    dataset = DatasetEvidence(mutableStore = true),
                    execution = ExecutionEvidence(preset = "paper-fast", broker = "paper"),
                    experiment =
                        ExperimentEvidence(
                            id = "exp-1",
                            trialCount = 2,
                            primaryMetric = "totalPnL",
                            splits =
                                mapOf(
                                    "train" to "2026-06-04T00:00:00Z/2026-06-04T04:00:00Z",
                                    "validation" to "2026-06-04T04:00:00Z/2026-06-04T08:00:00Z",
                                    "test" to "2026-06-04T08:00:00Z/2026-06-04T12:00:00Z",
                                ),
                            selectedLabel = "fast=3",
                            selectedParams = mapOf("fast" to "3"),
                        ),
                    promotion = PromotionEvidence(state = "candidate", rationale = "ready for paper"),
                ),
        )
}
