package com.qkt.cli

import com.qkt.backtest.BacktestResult
import java.io.PrintStream

sealed interface ReportFormat {
    data object Text : ReportFormat

    data object Json : ReportFormat
}

object ReportPrinter {
    fun print(
        result: BacktestResult,
        fmt: ReportFormat,
        out: PrintStream,
    ) {
        when (fmt) {
            ReportFormat.Text -> printText(result, out)
            ReportFormat.Json -> printJson(result, out)
        }
    }

    private fun printText(
        r: BacktestResult,
        out: PrintStream,
    ) {
        out.println("Trades:          ${r.global.tradeCount}")
        out.println("Final realized:  ${r.global.realizedTotal.toPlainString()}")
        out.println("Final unrealized:${r.global.unrealizedTotal.toPlainString()}")
        out.println("Total PnL:       ${r.global.totalPnL.toPlainString()}")
        out.println("Win rate:        ${r.global.winRate.toPlainString()}")
        out.println("Sharpe (daily):  ${r.global.sharpeRatio?.toPlainString() ?: "n/a"}")
        out.println("Max drawdown:    ${r.global.maxDrawdown.toPlainString()}")
    }

    private fun printJson(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val g = r.global
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"trades\":").append(g.tradeCount).append(',')
        sb.append("\"finalRealized\":").append(g.realizedTotal.toPlainString()).append(',')
        sb.append("\"finalUnrealized\":").append(g.unrealizedTotal.toPlainString()).append(',')
        sb.append("\"totalPnL\":").append(g.totalPnL.toPlainString()).append(',')
        sb.append("\"winRate\":").append(g.winRate.toPlainString()).append(',')
        sb.append("\"maxDrawdown\":").append(g.maxDrawdown.toPlainString()).append(',')
        sb.append("\"profitFactor\":").append(g.profitFactor?.toPlainString() ?: "null").append(',')
        sb.append("\"avgWin\":").append(g.avgWin.toPlainString()).append(',')
        sb.append("\"avgLoss\":").append(g.avgLoss.toPlainString()).append(',')
        sb.append("\"largestWin\":").append(g.largestWin.toPlainString()).append(',')
        sb.append("\"largestLoss\":").append(g.largestLoss.toPlainString()).append(',')
        sb.append("\"maxConsecutiveLosses\":").append(g.maxConsecutiveLosses).append(',')
        sb.append("\"sharpeRatio\":").append(g.sharpeRatio?.toPlainString() ?: "null").append(',')
        sb.append("\"calmarRatio\":").append(g.calmarRatio?.toPlainString() ?: "null").append(',')
        sb.append("\"cadence\":\"").append(r.cadence.name).append('"')
        sb.append('}')
        out.println(sb.toString())
    }
}
