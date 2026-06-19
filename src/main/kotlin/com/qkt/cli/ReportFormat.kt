package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BookAnalytics
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ConditionalAutocorr
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.Regime
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
        out.println("Sortino (annual): ${g.sortinoRatio?.toPlainString() ?: "n/a"}")
        out.println("Calmar:           ${g.calmarRatio?.toPlainString() ?: "n/a"}")
        out.println("Turnover (x cap): ${g.turnover.toPlainString()}")
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
        printPerStrategy(r, out)
        printBookAnalytics(r, out)
        printBookRisk(r, out)
        printAutocorr(r, out)
    }

    /** Book-risk summary (exposure peaks + book vol) for a portfolio run. Skipped when absent. */
    private fun printBookRisk(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val br = r.bookRisk ?: return
        out.println()
        out.println("Book risk")
        out.println("  book vol (annual):  ${br.bookVol?.toPlainString() ?: "n/a"}")
        out.println("  max gross exposure: ${br.maxGrossExposure.toPlainString()}")
        out.println("  max net exposure:   ${br.maxNetExposure.toPlainString()}")
        if (br.events.isNotEmpty()) out.println("  events:             ${br.events.size}")
    }

    /**
     * Cross-strategy relationships for a portfolio backtest: each strategy's share of book return and
     * of book risk, plus pairwise return correlation. Skipped on single-strategy runs (no book).
     */
    private fun printBookAnalytics(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val ba = r.bookAnalytics ?: return
        out.println()
        out.println("Book analytics")
        out.println("  contribution to return:")
        for ((id, v) in ba.contributionToReturn.entries.sortedBy { it.key }) {
            out.println("    ${id.padEnd(20)} ${v.toPlainString()}")
        }
        out.println("  risk contribution (PCTR):")
        for ((id, v) in ba.riskContribution.entries.sortedBy { it.key }) {
            out.println("    ${id.padEnd(20)} ${v.toPlainString()}")
        }
        if (ba.returnCorrelation.isNotEmpty()) {
            out.println("  return correlation:")
            for (p in ba.returnCorrelation) {
                out.println("    ${p.a} ~ ${p.b}: ${p.correlation.toPlainString()}")
            }
        }
    }

    /**
     * One line per child strategy of a portfolio backtest — the attribution the global block can't
     * show. Skipped on a single-strategy run, where the global block already says everything.
     */
    private fun printPerStrategy(
        r: BacktestResult,
        out: PrintStream,
    ) {
        if (r.perStrategy.size < 2) return
        out.println()
        out.println("Per-strategy")
        for ((id, s) in r.perStrategy.entries.sortedBy { it.key }) {
            out.println(
                "  ${id.padEnd(20)} " +
                    "PnL ${s.totalPnL.toPlainString().padStart(12)}  " +
                    "trades ${s.tradeCount.toString().padStart(5)}  " +
                    "Sharpe ${(s.sharpeRatio?.toPlainString() ?: "n/a").padStart(7)}  " +
                    "Sortino ${(s.sortinoRatio?.toPlainString() ?: "n/a").padStart(7)}  " +
                    "MaxDD ${s.maxDrawdown.toPlainString().padStart(7)}  " +
                    "win ${s.winRate.toPlainString()}",
            )
        }
    }

    /**
     * Lag-1 return autocorrelation block (#460), one section per symbol. Skipped entirely when no
     * symbol populated a bucket (e.g. a tick-only run with no candle window).
     */
    private fun printAutocorr(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val populated = r.conditionalAutocorr.filterValues { it.perHour.isNotEmpty() || it.perRegime.isNotEmpty() }
        if (populated.isEmpty()) return
        out.println()
        out.println("Lag-1 return autocorrelation")
        out.println("  high = |return| >= median; buckets with <3 returns omitted")
        for ((symbol, ac) in populated.entries.sortedBy { it.key }) {
            out.println("  $symbol")
            out.println("    by hour (UTC):")
            for ((hour, value) in ac.perHour.entries.sortedBy { it.key }) {
                val label = hour.toString().padStart(2, '0')
                out.println("      $label  ${value.toPlainString()}  (n=${ac.hourCounts[hour]})")
            }
            out.println("    by vol regime:")
            for (regime in Regime.entries) {
                val value = ac.perRegime[regime] ?: continue
                val label = regime.name.lowercase().padEnd(4)
                out.println("      $label  ${value.toPlainString()}  (n=${ac.regimeCounts[regime]})")
            }
        }
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
        sb.append("\"sortinoRatio\":").append(g.sortinoRatio?.toPlainString() ?: "null").append(',')
        sb.append("\"turnover\":").append(g.turnover.toPlainString()).append(',')
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
        sb.append("\"cadence\":\"").append(r.cadence.name).append("\",")
        sb.append("\"conditionalAutocorr\":").append(conditionalAutocorrJson(r.conditionalAutocorr)).append(',')
        sb.append("\"perStrategy\":{")
        sb.append(
            r.perStrategy.entries
                .sortedBy { it.key }
                .joinToString(",") { (id, s) -> "\"$id\":${strategyJson(s)}" },
        )
        sb.append("},")
        sb.append("\"bookAnalytics\":").append(bookAnalyticsJson(r.bookAnalytics)).append(',')
        sb.append("\"bookRisk\":").append(bookRiskJson(r.bookRisk)).append(',')
        sb.append("\"monteCarlo\":").append(monteCarloJson(g.monteCarlo))
        sb.append('}')
        out.println(sb.toString())
    }

    /**
     * The trade-bootstrap Monte-Carlo tail as a JSON object, or `null` when MC was unavailable
     * (fewer than the minimum trades). The drawdown percentiles let a sizing consumer reserve
     * against resampled drawdowns rather than the single realized path; the per-trade equity fan
     * is an HTML-visualization detail and is omitted.
     */
    private fun monteCarloJson(mc: MonteCarloSummary?): String {
        if (mc == null) return "null"
        return buildString {
            append("{\"simulations\":").append(mc.simulations)
            append(",\"finalEquityP5\":").append(mc.finalEquityP5.toPlainString())
            append(",\"finalEquityP50\":").append(mc.finalEquityP50.toPlainString())
            append(",\"finalEquityP95\":").append(mc.finalEquityP95.toPlainString())
            append(",\"maxDrawdownP5\":").append(mc.maxDrawdownP5.toPlainString())
            append(",\"maxDrawdownP95\":").append(mc.maxDrawdownP95.toPlainString())
            append(",\"probabilityNegativeFinal\":").append(mc.probabilityNegativeFinal.toPlainString())
            append("}")
        }
    }

    /** Book-risk summary object for `--json` (full series is in the `--report` book_risk.csv). */
    private fun bookRiskJson(br: com.qkt.backtest.BookRiskReport?): String {
        if (br == null) return "null"
        return buildString {
            append("{\"bookVol\":").append(br.bookVol?.toPlainString() ?: "null")
            append(",\"maxGrossExposure\":").append(br.maxGrossExposure.toPlainString())
            append(",\"maxNetExposure\":").append(br.maxNetExposure.toPlainString())
            append(",\"samples\":").append(br.series.size)
            append(",\"events\":").append(br.events.size)
            append("}")
        }
    }

    /** Compact per-strategy attribution object for `--json` — the full report is in `--report`. */
    private fun strategyJson(s: PerformanceReport): String =
        buildString {
            append("{\"totalPnL\":").append(s.totalPnL.toPlainString())
            append(",\"realized\":").append(s.realizedTotal.toPlainString())
            append(",\"unrealized\":").append(s.unrealizedTotal.toPlainString())
            append(",\"trades\":").append(s.tradeCount)
            append(",\"winRate\":").append(s.winRate.toPlainString())
            append(",\"sharpeRatio\":").append(s.sharpeRatio?.toPlainString() ?: "null")
            append(",\"sortinoRatio\":").append(s.sortinoRatio?.toPlainString() ?: "null")
            append(",\"calmarRatio\":").append(s.calmarRatio?.toPlainString() ?: "null")
            append(",\"maxDrawdown\":").append(s.maxDrawdown.toPlainString())
            append(",\"maxDailyDrawdown\":").append(s.maxDailyDrawdown.toPlainString())
            append(",\"turnover\":").append(s.turnover.toPlainString())
            append(",\"commissionPaid\":").append(s.commissionPaid.toPlainString())
            append("}")
        }

    /** Cross-strategy book analytics as a JSON object, or null on a single-strategy run. */
    private fun bookAnalyticsJson(ba: BookAnalytics?): String {
        if (ba == null) return "null"
        return buildString {
            append("{\"contributionToReturn\":").append(mapNumberJson(ba.contributionToReturn))
            append(",\"riskContribution\":").append(mapNumberJson(ba.riskContribution))
            append(",\"drawdownContribution\":").append(mapNumberJson(ba.drawdownContribution))
            append(",\"returnCorrelation\":[")
            append(
                ba.returnCorrelation.joinToString(",") {
                    "{\"a\":\"${it.a}\",\"b\":\"${it.b}\",\"correlation\":${it.correlation.toPlainString()}}"
                },
            )
            append("]}")
        }
    }

    private fun mapNumberJson(m: Map<String, java.math.BigDecimal>): String =
        buildString {
            append("{")
            append(
                m.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" },
            )
            append("}")
        }

    /**
     * Lag-1 return autocorrelation (#460) as a JSON object keyed by symbol, e.g.
     * `{"XAUUSD":{"perHour":{"13":-1.0},"perRegime":{"high":0.31},"hourCounts":{"13":120},
     * "regimeCounts":{"high":600,"low":600}}}`. Empty object when no symbol populated a bucket.
     * Keys are sorted for deterministic output, matching the `dailyPnL` convention.
     */
    private fun conditionalAutocorrJson(bySymbol: Map<String, ConditionalAutocorr>): String =
        buildString {
            append('{')
            append(
                bySymbol.entries
                    .sortedBy { it.key }
                    .joinToString(",") { (symbol, ac) -> "\"$symbol\":${autocorrObject(ac)}" },
            )
            append('}')
        }

    private fun autocorrObject(ac: ConditionalAutocorr): String =
        buildString {
            append("{\"perHour\":{")
            append(
                ac.perHour.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" },
            )
            append("},\"perRegime\":{")
            append(
                ac.perRegime.entries
                    .sortedBy { it.key.name }
                    .joinToString(",") { "\"${it.key.name.lowercase()}\":${it.value.toPlainString()}" },
            )
            append("},\"hourCounts\":{")
            append(
                ac.hourCounts.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value}" },
            )
            append("},\"regimeCounts\":{")
            append(
                ac.regimeCounts.entries
                    .sortedBy { it.key.name }
                    .joinToString(",") { "\"${it.key.name.lowercase()}\":${it.value}" },
            )
            append("}}")
        }
}
