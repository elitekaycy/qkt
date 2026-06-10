package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import java.io.PrintStream

/** Output format selector for `qkt backtest` console reports. */
sealed interface ReportFormat {
    /** Aligned plaintext summary — the default. */
    data object Text : ReportFormat

    /** Single-line JSON — for piping into tooling. */
    data object Json : ReportFormat
}

/** Renders a [BacktestResult] in [ReportFormat.Text] or [ReportFormat.Json] form. */
object ReportPrinter {
    /**
     * Writes [result] in [fmt] form to [out]. [brokerKind] drives the execution-assumptions
     * disclosure — what the fills did and didn't model — so the report never reads as more
     * realistic than it is (#336).
     */
    fun print(
        result: BacktestResult,
        fmt: ReportFormat,
        out: PrintStream,
        brokerKind: BrokerKind,
    ) {
        when (fmt) {
            ReportFormat.Text -> printText(result, out, brokerKind)
            ReportFormat.Json -> printJson(result, out, brokerKind)
        }
    }

    /** One-line description of what the broker's fills modeled. */
    private fun executionModel(brokerKind: BrokerKind): String =
        when (brokerKind) {
            BrokerKind.PAPER -> "paper — fills at mid price; no spread, no slippage modeled"
            BrokerKind.MT5_SIM -> "mt5-sim — synthetic spread + configurable slippage"
        }

    private fun printText(
        r: BacktestResult,
        out: PrintStream,
        brokerKind: BrokerKind,
    ) {
        val g = r.global
        out.println("Trades:           ${g.tradeCount}")
        out.println("Final realized:   ${g.realizedTotal.toPlainString()}   (net of commission)")
        out.println("Final unrealized: ${g.unrealizedTotal.toPlainString()}")
        out.println("Total PnL:        ${g.totalPnL.toPlainString()}")
        out.println("Commission paid:  ${g.commissionPaid.toPlainString()}")
        out.println("Win rate:         ${g.winRate.toPlainString()}")
        out.println("Sharpe (annual):  ${g.sharpeRatio?.toPlainString() ?: "n/a"}")
        out.println("Calmar:           ${g.calmarRatio?.toPlainString() ?: "n/a"}")
        out.println("Max drawdown:     ${g.maxDrawdown.toPlainString()}")
        out.println("Max daily DD:     ${g.maxDailyDrawdown.toPlainString()}")
        if (r.halts.isNotEmpty()) {
            out.println("Risk halts:       ${r.halts.size}")
            for (h in r.halts) {
                val ts = java.time.Instant.ofEpochMilli(h.timestamp)
                out.println("  $ts  ${h.reason}${h.strategyId?.let { " [$it]" } ?: ""}")
            }
        }
        out.println()
        out.println("Assumptions & conventions")
        out.println("  Execution:  ${executionModel(brokerKind)}")
        out.println("  Commission: ${commissionNote(g.commissionPaid)}")
        out.println("  Win rate:   wins / decided trades; break-even trades excluded")
        out.println("  Calmar:     total return / max drawdown (NOT annualized)")
        out.println("  Sharpe:     annualized from average sample spacing; risk-free rate 0")
    }

    private fun commissionNote(commissionPaid: java.math.BigDecimal): String =
        if (commissionPaid.signum() > 0) {
            "$commissionPaid charged (per-lot, from instruments.yaml)"
        } else {
            "none modeled — set commissionPerLot in instruments.yaml for cost-realistic PnL"
        }

    private fun printJson(
        r: BacktestResult,
        out: PrintStream,
        brokerKind: BrokerKind,
    ) {
        val g = r.global
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"trades\":").append(g.tradeCount).append(',')
        sb.append("\"finalRealized\":").append(g.realizedTotal.toPlainString()).append(',')
        sb.append("\"finalUnrealized\":").append(g.unrealizedTotal.toPlainString()).append(',')
        sb.append("\"totalPnL\":").append(g.totalPnL.toPlainString()).append(',')
        sb.append("\"commissionPaid\":").append(g.commissionPaid.toPlainString()).append(',')
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
        sb.append("\"executionModel\":\"").append(brokerKind.name.lowercase()).append("\",")
        sb.append("\"maxDailyDrawdown\":").append(g.maxDailyDrawdown.toPlainString()).append(',')
        sb.append("\"dailyPnL\":{")
        sb.append(
            g.dailyPnL.entries
                .sortedBy { it.key }
                .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" },
        )
        sb.append("},")
        sb.append("\"halts\":").append(r.halts.size).append(',')
        sb.append("\"cadence\":\"").append(r.cadence.name).append('"')
        sb.append('}')
        out.println(sb.toString())
    }
}
