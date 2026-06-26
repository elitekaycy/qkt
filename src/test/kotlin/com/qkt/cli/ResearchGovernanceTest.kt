package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.sweep.SweepRun
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResearchGovernanceTest {
    @Test
    fun `unstable neighborhood warning triggers when adjacent grid cells collapse`() {
        val runs =
            listOf(
                run("fast=3", "3", "100"),
                run("fast=2", "2", "10"),
                run("fast=4", "4", "15"),
            )

        val warning =
            ResearchGovernance.unstableNeighborhoodWarning(
                ranked = runs,
                axes = mapOf("fast" to listOf("2", "3", "4")),
                rank = RankMetric.TOTAL_PNL,
            )

        assertThat(warning)
            .contains("adjacent grid")
            .contains("unstable-neighborhood")
    }

    private fun run(
        label: String,
        fast: String,
        pnl: String,
    ): SweepRun<ParamGrid.Combo> =
        SweepRun(
            label = label,
            config = ParamGrid.Combo(label, mapOf("fast" to fast)),
            result =
                BacktestResult(
                    trades = emptyList(),
                    rejections = emptyList(),
                    finalPositions = emptyMap(),
                    global = report(pnl),
                    perStrategy = emptyMap(),
                    cadence = SampleCadence.TICK,
                ),
        )

    private fun report(pnl: String): PerformanceReport =
        PerformanceReport(
            realizedTotal = BigDecimal(pnl),
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal(pnl),
            tradeCount = 1,
            winRate = BigDecimal.ONE,
            maxDrawdown = BigDecimal.ZERO,
            profitFactor = BigDecimal.ONE,
            avgWin = BigDecimal(pnl),
            avgLoss = BigDecimal.ZERO,
            largestWin = BigDecimal(pnl),
            largestLoss = BigDecimal.ZERO,
            maxConsecutiveLosses = 0,
            sharpeRatio = null,
            calmarRatio = null,
            equityCurve = listOf(EquitySample(0L, BigDecimal("10000"))),
        )
}
