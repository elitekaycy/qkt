package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionFacts
import com.qkt.backtest.report.ExecutionFactsText
import com.qkt.backtest.report.HumanFormat
import java.io.PrintStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The default ~20-line `qkt backtest` view (#1370): headline numbers with units, open positions
 * stated plainly, costs and halts only when they happened, and the fill assumptions the run actually
 * made ([ExecutionFactsText]). Today's full output lives behind
 * `--verbose`; `--json` and the report bundle are unchanged.
 */
internal object ConciseReportPrinter {
    fun print(
        r: BacktestResult,
        out: PrintStream,
        brokerKind: BrokerKind,
        futures: Set<String> = emptySet(),
        options: Set<String> = emptySet(),
    ) {
        val g = r.global
        val currency = r.accounting?.accountCurrency
        val start = g.equityCurve.firstOrNull()?.equity
        val facts = r.executionFacts ?: ExecutionFacts.assumed(brokerKind, futures, options)

        out.println("Backtest   ${windowLine(r)}")
        if (start != null) out.println("Account    ${HumanFormat.money(start, currency, signed = false)}")
        out.println()
        out.println("Result")
        out.println("  ${row("Profit")}${HumanFormat.money(g.totalPnL, currency)}${returnPct(g.totalPnL, start)}")
        out.println("  ${row("Round trips")}${roundTrips(r)}")
        out.println("  ${row("Open at the end")}${openPositions(r, currency)}")
        out.println("  ${row("Worst drop")}${HumanFormat.percent(g.maxDrawdown)}    biggest fall from a peak")
        out.println("  ${row("Worst day")}${HumanFormat.percent(g.maxDailyDrawdown)}")
        out.println("  ${row("Sharpe ratio")}${HumanFormat.ratio(g.sharpeRatio)}     return per unit of risk")
        out.println("  ${row("Costs")}${ExecutionFactsText.costs(facts, g, currency)}")
        if (r.halts.isNotEmpty()) {
            out.println("  ${row("Halts")}${r.halts.size} halt(s); trading stopped partway")
        }
        r.runawayBreaker?.let { breaker ->
            if (!breaker.enforceLiveBreakers && breaker.trips.isNotEmpty()) {
                out.println("  LIVE BEHAVIOR WARNING: the runaway breaker would have halted this strategy")
            }
        }
        out.println()
        out.println("Assumptions")
        ExecutionFactsText.padded(ExecutionFactsText.lines(facts)).forEach(out::println)
        if (facts.broker == BrokerKind.PAPER && (facts.brokerSymbols.isNotEmpty() || !facts.recorded)) {
            out.println("  Paper fills are optimistic. Try --broker mt5-sim for a realistic fill model.")
        }
        out.println()
        out.println("More detail: --verbose (full output), --report-dir DIR (files), --json (for tools).")
    }

    private fun row(label: String): String = label.padEnd(19)

    private fun windowLine(r: BacktestResult): String {
        val curve = r.global.equityCurve
        if (curve.isEmpty()) return "(no equity samples)"
        // The daily rows know which day a midnight close belongs to; the curve's last stamp is the next day's 00:00.
        val from =
            r.dailyEquity.firstOrNull()?.date
                ?: LocalDate.ofInstant(Instant.ofEpochMilli(curve.first().timestamp), ZoneOffset.UTC)
        val to =
            r.dailyEquity.lastOrNull()?.date
                ?: LocalDate.ofInstant(Instant.ofEpochMilli(curve.last().timestamp), ZoneOffset.UTC)
        val days =
            r.dailyEquity.size
                .takeIf { it > 0 }
                ?.let { " ($it trading ${if (it == 1) "day" else "days"})" } ?: ""
        return "$from to $to$days"
    }

    private fun returnPct(
        total: BigDecimal,
        start: BigDecimal?,
    ): String {
        if (start == null || start.signum() <= 0) return ""
        return "   (${HumanFormat.percent(total.divide(start, MathContext.DECIMAL64), signed = true)})"
    }

    /** Positions closed, from the same round trips and win rate as `result.json` and report.html (#1437, #1452). */
    private fun roundTrips(r: BacktestResult): String {
        val g = r.global
        if (g.roundTrips == 0) return "0 closed"
        val pct = HumanFormat.percent(g.winRate)
        if (g.roundTripsBreakEven == 0) return "${g.roundTrips} closed, ${g.roundTripsWon} won ($pct)"
        return "${g.roundTrips} closed, ${g.roundTripsWon} won, ${g.roundTripsBreakEven} break-even ($pct of the rest)"
    }

    private fun openPositions(
        r: BacktestResult,
        currency: String?,
    ): String {
        val open = r.finalPositions.values.filter { it.quantity.signum() != 0 }
        if (open.isEmpty()) return "none"
        val shown = open.take(3).joinToString(", ") { "${it.symbol} ${signedQty(it.quantity)}" }
        val more = if (open.size > 3) " (+${open.size - 3} more)" else ""
        return "$shown$more  worth ${HumanFormat.money(r.global.unrealizedTotal, currency)}"
    }

    private fun signedQty(qty: BigDecimal): String {
        val body = qty.abs().stripTrailingZeros().toPlainString()
        return if (qty.signum() < 0) "-$body" else "+$body"
    }
}
