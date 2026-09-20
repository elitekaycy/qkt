package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.Regime
import java.io.PrintStream

/**
 * The aligned plaintext form of `qkt backtest` output: headline metrics, halts, runaway breaker,
 * replay inputs, the assumptions behind the numbers, then the evidence, per-strategy, book and
 * autocorrelation blocks.
 */
internal object TextReportPrinter {
    fun print(
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
        r.inputSummary?.let { inputs ->
            out.println()
            out.println("Replay inputs")
            out.println("  feed ticks:      ${inputs.attemptedFeedTicks}")
            out.println("  live ticks:      ${inputs.liveTicks}")
            out.println("  warmup ticks:    ${inputs.warmupTicks}")
            out.println("  warmup candles:  ${inputs.warmupCandles}")
            out.println("  live candles:    ${inputs.liveCandles}")
            out.println("  malformed ticks: ${inputs.malformedTicks}")
            out.println("  late ticks:      ${inputs.droppedLateTicks}")
            for ((stream, count) in inputs.streamCandles.toSortedMap()) {
                out.println("  stream $stream: $count candles")
            }
            for ((stream, count) in inputs.strategyCandleEvaluations.toSortedMap()) {
                out.println("  strategy evaluation $stream: $count candles")
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
        TextEvidencePrinter.print(r, out)
        TextBookPrinter.printPerStrategy(r, out)
        TextBookPrinter.printBookAnalytics(r, out)
        TextBookPrinter.printBookRisk(r, out)
        printAutocorr(r, out)
    }

    /** One-line description of what the broker's fills modeled. */
    private fun executionModel(brokerKind: BrokerKind): String =
        when (brokerKind) {
            BrokerKind.PAPER -> "paper — fills at mid price; no spread, no slippage modeled"
            BrokerKind.MT5_SIM -> "mt5-sim — synthetic spread + configurable slippage"
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
}
