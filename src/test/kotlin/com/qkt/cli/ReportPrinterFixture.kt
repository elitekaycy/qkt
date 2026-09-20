package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal

abstract class ReportPrinterFixture {
    protected fun report(
        commissionPaid: String,
        swapPaid: String = "0",
    ): PerformanceReport =
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
            equityCurve = listOf(EquitySample(0L, BigDecimal("100"))),
            commissionPaid = BigDecimal(commissionPaid),
            swapPaid = BigDecimal(swapPaid),
        )

    protected fun result(
        commissionPaid: String = "0",
        swapPaid: String = "0",
    ): BacktestResult =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = report(commissionPaid, swapPaid),
            perStrategy = emptyMap(),
            cadence = SampleCadence.TICK,
        )

    protected fun render(
        fmt: ReportFormat,
        brokerKind: BrokerKind,
        commissionPaid: String = "0",
        swapPaid: String = "0",
    ): String {
        val buf = ByteArrayOutputStream()
        ReportPrinter.print(result(commissionPaid, swapPaid), fmt, PrintStream(buf), brokerKind)
        return buf.toString()
    }
}
