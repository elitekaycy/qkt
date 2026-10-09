package com.qkt.backtest.report

import com.qkt.accounting.AccountingSnapshot
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.positions.Position
import java.math.BigDecimal

abstract class HtmlHumanValuesFixture {
    protected fun drawdown(
        peakMs: Long,
        depth: String,
        durationMs: Long,
    ) = DrawdownPeriod(
        peakTimestamp = peakMs,
        peakEquity = BigDecimal("15000"),
        troughTimestamp = peakMs + 1000L,
        troughEquity = BigDecimal("14000"),
        recoveryTimestamp = peakMs + 2000L,
        depthPct = BigDecimal(depth),
        durationMs = durationMs,
        ongoing = false,
    )

    protected fun result(
        trades: Int = 1,
        drawdowns: Int = 1,
    ): BacktestResult {
        val global =
            PerformanceReport(
                realizedTotal = BigDecimal.ZERO,
                unrealizedTotal = BigDecimal("4423.25"),
                totalPnL = BigDecimal("4423.25"),
                tradeCount = trades,
                winRate = BigDecimal.ZERO,
                maxDrawdown = BigDecimal("0.43244333"),
                profitFactor = null,
                avgWin = BigDecimal.ZERO,
                avgLoss = BigDecimal.ZERO,
                largestWin = BigDecimal.ZERO,
                largestLoss = BigDecimal.ZERO,
                maxConsecutiveLosses = 0,
                sharpeRatio = BigDecimal("0.63522786"),
                calmarRatio = BigDecimal("1.02285079"),
                equityCurve =
                    listOf(
                        EquitySample(1_727_686_800_000L, BigDecimal("10000")),
                        EquitySample(1_727_687_800_000L, BigDecimal("14423.25")),
                    ),
                drawdownPeriods = (0 until drawdowns).map { drawdown(1_727_686_800_000L + it, "-0.0$it", 16_613_100_000L) },
                commissionPaid = BigDecimal("51.20"),
            )
        return BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions =
                mapOf(
                    "BACKTEST:EURUSD" to
                        Position(symbol = "BACKTEST:EURUSD", quantity = BigDecimal.ONE, avgEntryPrice = BigDecimal("1.10")),
                ),
            global = global,
            perStrategy = emptyMap(),
            cadence = SampleCadence.TICK,
            accounting =
                AccountingSnapshot(
                    accountCurrency = "USD",
                    missingPolicy = "fail",
                    source = "market",
                    configuredSymbols = emptyMap(),
                    conversions = emptyList(),
                    warnings = emptyList(),
                ),
        )
    }

    protected fun render(result: BacktestResult): String = HtmlReportWriter(HtmlReportConfig()).render(result)
}
