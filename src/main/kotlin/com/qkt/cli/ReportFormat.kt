package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BookAnalytics
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ConditionalAutocorr
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.Regime
import com.qkt.backtest.report.TradeAuditSummaries
import com.qkt.evidence.EvidenceJson
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
    private const val RESULT_SCHEMA = "qkt-backtest-result-v1"
    private const val RESULT_SCHEMA_VERSION = 1

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
        out.println("Final realized:   ${g.realizedTotal.toPlainString()}   (net of commission and swap)")
        out.println("Final unrealized: ${g.unrealizedTotal.toPlainString()}")
        out.println("Total PnL:        ${g.totalPnL.toPlainString()}")
        out.println("Commission paid:  ${g.commissionPaid.toPlainString()}")
        out.println("Swap paid:        ${g.swapPaid.toPlainString()}")
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
        r.runawayBreaker?.let { breaker ->
            val mode = if (breaker.enforceLiveBreakers) "enforced" else "observe-only"
            out.println(
                "Runaway breaker:  $mode; ${breaker.maxRoundTrips} round trips/" +
                    "${breaker.roundTripWindowMs / 1000}s; ${breaker.maxRejections} broker rejections/" +
                    "${breaker.rejectionWindowMs / 1000}s",
            )
            if (!breaker.enforceLiveBreakers && breaker.trips.isNotEmpty()) {
                val first = breaker.trips.first()
                out.println(
                    "LIVE BEHAVIOR WARNING: the runaway breaker would have halted this strategy " +
                        "${breaker.trips.size} time(s); first at " +
                        "${java.time.Instant.ofEpochMilli(first.timestampMs)} [${first.strategyId}]: ${first.reason()}",
                )
            }
        }
        out.println()
        out.println("Assumptions & conventions")
        out.println("  Execution:  ${executionModel(brokerKind)}")
        out.println("  Commission: ${commissionNote(g.commissionPaid)}")
        out.println("  Swap:       ${swapNote(g.swapPaid)}")
        out.println("  Win rate:   wins / decided trades; break-even trades excluded")
        out.println("  Calmar:     total return / max drawdown (NOT annualized)")
        out.println("  Sharpe:     annualized from average sample spacing; risk-free rate 0")
        printEvidence(r, out)
        printPerStrategy(r, out)
        printBookAnalytics(r, out)
        printBookRisk(r, out)
        printAutocorr(r, out)
    }

    private fun printEvidence(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val e = r.evidence ?: return
        out.println()
        out.println("Run evidence")
        out.println("  qkt:       ${e.qktVersion} (${e.gitSha})")
        out.println("  pct DSL:   ${e.dslPercentConvention}")
        out.println("  strategy:  ${e.strategyHash}")
        e.configHash?.let { out.println("  config:    $it") }
        e.dataset?.let {
            val label = it.id ?: if (it.mutableStore) "mutable local store" else "not specified"
            out.println("  dataset:   $label")
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.execution?.let {
            out.println("  execution: ${it.preset} (${it.broker})")
            it.fillPriceSource?.let { v -> out.println("  fills:     $v") }
            it.latencyModel?.let { v -> out.println("  latency:   $v") }
            it.slippageModel?.let { v -> out.println("  slippage:  $v") }
            it.rejectionModel?.let { v -> out.println("  rejects:   $v") }
            it.partialFillModel?.let { v -> out.println("  partials:  $v") }
            it.venueRules?.let { v -> out.println("  venue:     $v") }
            it.commissionModel?.let { v -> out.println("  costs:     $v") }
            it.financingModel?.let { v -> out.println("  financing: $v") }
            it.ocoMode?.let { v -> out.println("  oco:       $v") }
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.accounting?.let {
            it.accountCurrency?.let { ccy -> out.println("  account:   $ccy") }
            it.missingPolicy?.let { policy -> out.println("  fx policy: $policy") }
            it.source?.let { source -> out.println("  fx source: $source") }
            if (it.configuredFxSymbols.isNotEmpty()) {
                out.println("  fx symbols:${it.configuredFxSymbols}")
            }
            for ((pair, detail) in it.conversions.entries.sortedBy { entry -> entry.key }) {
                out.println("  fx $pair: $detail")
            }
            for (warning in it.warnings) out.println("  warning:   $warning")
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.experiment?.let {
            out.println("  experiment:${it.id ?: "unspecified"}")
            it.trialCount?.let { n -> out.println("  trials:    $n") }
            it.primaryMetric?.let { metric -> out.println("  metric:    $metric") }
            if (it.splits.isNotEmpty()) {
                for ((name, window) in it.splits.entries) {
                    out.println("  split.$name: $window")
                }
            }
            it.selectedLabel?.let { label -> out.println("  selected:  $label ${it.selectedParams}") }
            for (warning in it.warnings) out.println("  warning:   $warning")
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        e.promotion?.let {
            it.state?.let { state -> out.println("  promotion: $state") }
            it.rationale?.let { rationale -> out.println("  rationale: $rationale") }
            it.warning?.let { warning -> out.println("  warning:   $warning") }
        }
        for (warning in e.warnings) out.println("  warning:   $warning")
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

    private fun swapNote(swapPaid: java.math.BigDecimal): String =
        when {
            swapPaid.signum() > 0 -> "$swapPaid charged (signed points from instruments.yaml)"
            swapPaid.signum() < 0 -> "${swapPaid.negate()} credited (signed points from instruments.yaml)"
            else -> "none accrued"
        }

    private fun printJson(
        r: BacktestResult,
        out: PrintStream,
        brokerKind: BrokerKind,
    ) {
        val g = r.global
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"schema\":\"").append(RESULT_SCHEMA).append("\",")
        sb.append("\"schemaVersion\":").append(RESULT_SCHEMA_VERSION).append(',')
        sb.append("\"trades\":").append(g.tradeCount).append(',')
        sb.append("\"finalRealized\":").append(g.realizedTotal.toPlainString()).append(',')
        sb.append("\"finalUnrealized\":").append(g.unrealizedTotal.toPlainString()).append(',')
        sb.append("\"totalPnL\":").append(g.totalPnL.toPlainString()).append(',')
        sb.append("\"commissionPaid\":").append(g.commissionPaid.toPlainString()).append(',')
        sb.append("\"swapPaid\":").append(g.swapPaid.toPlainString()).append(',')
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
        sb.append("\"runawayBreaker\":").append(runawayBreakerJson(r.runawayBreaker)).append(',')
        sb.append("\"cadence\":\"").append(r.cadence.name).append("\",")
        sb.append("\"conditionalAutocorr\":").append(conditionalAutocorrJson(r.conditionalAutocorr)).append(',')
        sb.append("\"tradeSummary\":").append(tradeSummaryJson(r)).append(',')
        sb.append("\"global\":").append(reportJson(g)).append(',')
        sb.append("\"perStrategy\":{")
        sb.append(
            r.perStrategy.entries
                .sortedBy { it.key }
                .joinToString(",") { (id, s) -> "${jsonString(id)}:${strategyJson(s)}" },
        )
        sb.append("},")
        sb.append("\"bookAnalytics\":").append(bookAnalyticsJson(r.bookAnalytics)).append(',')
        sb.append("\"bookRisk\":").append(bookRiskJson(r.bookRisk)).append(',')
        sb.append("\"evidence\":").append(r.evidence?.let(EvidenceJson::render) ?: "null").append(',')
        sb.append("\"monteCarlo\":").append(monteCarloJson(g.monteCarlo))
        sb.append('}')
        out.println(sb.toString())
    }

    private fun runawayBreakerJson(report: com.qkt.backtest.RunawayBreakerReport?): String {
        if (report == null) return "null"
        return buildString {
            append("{\"enforceLiveBreakers\":").append(report.enforceLiveBreakers)
            append(",\"maxRoundTrips\":").append(report.maxRoundTrips)
            append(",\"roundTripWindowMs\":").append(report.roundTripWindowMs)
            append(",\"maxRejections\":").append(report.maxRejections)
            append(",\"rejectionWindowMs\":").append(report.rejectionWindowMs)
            append(",\"trips\":[")
            append(
                report.trips.joinToString(",") { trip ->
                    "{\"timestampMs\":${trip.timestampMs},\"strategyId\":" +
                        "${jsonString(trip.strategyId)}," +
                        "\"rule\":${jsonString(trip.rule.name.lowercase())},\"count\":${trip.count}," +
                        "\"threshold\":${trip.threshold},\"windowMs\":${trip.windowMs}}"
                },
            )
            append("]}")
        }
    }

    private fun tradeSummaryJson(result: BacktestResult): String {
        val summary = TradeAuditSummaries.from(result)

        return buildString {
            append("{\"fills\":").append(summary.fills)
            append(",\"buyFills\":").append(summary.buyFills)
            append(",\"sellFills\":").append(summary.sellFills)
            append(",\"sideAttribution\":").append(jsonString(summary.sideAttribution))
            append(",\"longEntryFills\":").append(summary.longEntryFills)
            append(",\"shortEntryFills\":").append(summary.shortEntryFills)
            append(",\"longExitFills\":").append(summary.longExitFills)
            append(",\"shortExitFills\":").append(summary.shortExitFills)
            append(",\"unknownPositionFills\":").append(summary.unknownPositionFills)
            append(",\"positionAttribution\":").append(jsonString(summary.positionAttribution))
            append(",\"buyRealized\":").append(summary.buyRealized.toPlainString())
            append(",\"sellRealized\":").append(summary.sellRealized.toPlainString())
            append(",\"grossProfit\":").append(summary.grossProfit.toPlainString())
            append(",\"grossLoss\":").append(summary.grossLoss.toPlainString())
            append(",\"rejections\":").append(summary.rejections)
            append(",\"rejectionRate\":").append(summary.rejectionRate?.toPlainString() ?: "null")
            append(",\"riskAuditedFills\":").append(summary.riskAuditedFills)
            append(",\"minRiskUsd\":").append(summary.minRiskUsd?.toPlainString() ?: "null")
            append(",\"avgRiskUsd\":").append(summary.avgRiskUsd?.toPlainString() ?: "null")
            append(",\"maxRiskUsd\":").append(summary.maxRiskUsd?.toPlainString() ?: "null")
            append(",\"tradedNotional\":").append(summary.tradedNotional.toPlainString())
            append(",\"maxFillNotional\":").append(summary.maxFillNotional?.toPlainString() ?: "null")
            append("}")
        }
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
        reportJson(
            s,
            aliases =
                mapOf(
                    "realized" to s.realizedTotal.toPlainString(),
                    "unrealized" to s.unrealizedTotal.toPlainString(),
                    "trades" to s.tradeCount.toString(),
                ),
        )

    private fun reportJson(
        r: PerformanceReport,
        aliases: Map<String, String> = emptyMap(),
    ): String =
        buildString {
            append("{\"realizedTotal\":").append(r.realizedTotal.toPlainString())
            append(",\"unrealizedTotal\":").append(r.unrealizedTotal.toPlainString())
            append(",\"totalPnL\":").append(r.totalPnL.toPlainString())
            append(",\"commissionPaid\":").append(r.commissionPaid.toPlainString())
            append(",\"swapPaid\":").append(r.swapPaid.toPlainString())
            append(",\"tradeCount\":").append(r.tradeCount)
            append(",\"winRate\":").append(r.winRate.toPlainString())
            append(",\"maxDrawdown\":").append(r.maxDrawdown.toPlainString())
            append(",\"profitFactor\":").append(r.profitFactor?.toPlainString() ?: "null")
            append(",\"avgWin\":").append(r.avgWin.toPlainString())
            append(",\"avgLoss\":").append(r.avgLoss.toPlainString())
            append(",\"largestWin\":").append(r.largestWin.toPlainString())
            append(",\"largestLoss\":").append(r.largestLoss.toPlainString())
            append(",\"maxConsecutiveLosses\":").append(r.maxConsecutiveLosses)
            append(",\"sharpeRatio\":").append(r.sharpeRatio?.toPlainString() ?: "null")
            append(",\"calmarRatio\":").append(r.calmarRatio?.toPlainString() ?: "null")
            append(",\"sortinoRatio\":").append(r.sortinoRatio?.toPlainString() ?: "null")
            append(",\"turnover\":").append(r.turnover.toPlainString())
            append(",\"maxDailyDrawdown\":").append(r.maxDailyDrawdown.toPlainString())
            append(",\"dailyPnL\":").append(dailyPnlJson(r.dailyPnL))
            append(",\"drawdownPeriods\":").append(drawdownPeriodsJson(r.drawdownPeriods))
            append(",\"monteCarlo\":").append(monteCarloJson(r.monteCarlo))
            append(",\"equityCurve\":").append(equityCurveJson(r.equityCurve))
            for ((name, value) in aliases.entries.sortedBy { it.key }) {
                append(',').append(jsonString(name)).append(':').append(value)
            }
            append("}")
        }

    private fun dailyPnlJson(dailyPnL: Map<java.time.LocalDate, java.math.BigDecimal>): String =
        buildString {
            append("{")
            append(
                dailyPnL.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" },
            )
            append("}")
        }

    private fun drawdownPeriodsJson(periods: List<com.qkt.backtest.DrawdownPeriod>): String =
        buildString {
            append("[")
            append(
                periods.joinToString(",") {
                    "{\"peakTimestamp\":${it.peakTimestamp}," +
                        "\"peakIso\":${isoJson(it.peakTimestamp)}," +
                        "\"troughTimestamp\":${it.troughTimestamp}," +
                        "\"troughIso\":${isoJson(it.troughTimestamp)}," +
                        "\"recoveryTimestamp\":${it.recoveryTimestamp?.toString() ?: "null"}," +
                        "\"recoveryIso\":${
                            it.recoveryTimestamp
                                ?.let(::isoJson)
                                ?: "null"
                        }," +
                        "\"depthPct\":${it.depthPct.toPlainString()}," +
                        "\"durationMs\":${it.durationMs}," +
                        "\"ongoing\":${it.ongoing}}"
                },
            )
            append("]")
        }

    private fun equityCurveJson(curve: List<com.qkt.backtest.EquitySample>): String =
        buildString {
            append("[")
            append(
                curve.joinToString(",") {
                    "{\"timestamp\":${it.timestamp}," +
                        "\"iso\":${isoJson(it.timestamp)}," +
                        "\"equity\":${it.equity.toPlainString()}}"
                },
            )
            append("]")
        }

    private fun isoJson(epochMs: Long): String =
        jsonString(
            java.time.Instant
                .ofEpochMilli(epochMs)
                .toString(),
        )

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
                    "{\"a\":${jsonString(it.a)},\"b\":${jsonString(it.b)}," +
                        "\"correlation\":${it.correlation.toPlainString()}}"
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
                    .joinToString(",") { "${jsonString(it.key)}:${it.value.toPlainString()}" },
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
                    .joinToString(",") { (symbol, ac) -> "${jsonString(symbol)}:${autocorrObject(ac)}" },
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

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
