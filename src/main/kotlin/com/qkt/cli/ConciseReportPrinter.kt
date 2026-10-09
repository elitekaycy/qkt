package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.report.HumanFormat
import java.io.PrintStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The default ~20-line `qkt backtest` view (#1370): headline numbers with units, open positions
 * stated plainly, costs and halts only when they happened. Today's full output lives behind
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
        out.println("  ${row("Costs")}${costs(r, currency)}")
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
        when (brokerKind) {
            BrokerKind.PAPER -> {
                out.println("  Fills at the middle price, no spread or slippage: results are optimistic.")
                out.println("  Try --broker mt5-sim for a realistic fill model.")
            }
            BrokerKind.MT5_SIM -> out.println("  Fills use a synthetic spread plus configured slippage.")
        }
        if (futures.isNotEmpty()) {
            out.println(
                "  Futures:    exchange simulator — executable price + slippage, root fees per fill, rolls as roll costs",
            )
        }
        if (options.isNotEmpty()) {
            out.println(
                "  Options:    option venue — next chain snapshot's bid/ask, capped venue fees, cash settlement at delivery",
            )
        }
        out.println()
        out.println("More detail: --verbose (full output), --report-dir DIR (files), --json (for tools).")
    }

    private fun row(label: String): String = label.padEnd(19)

    private fun windowLine(r: BacktestResult): String {
        val curve = r.global.equityCurve
        if (curve.isEmpty()) return "(no equity samples)"
        val from = LocalDate.ofInstant(Instant.ofEpochMilli(curve.first().timestamp), ZoneOffset.UTC)
        val to = LocalDate.ofInstant(Instant.ofEpochMilli(curve.last().timestamp), ZoneOffset.UTC)
        val days =
            r.dailyEquity.size.takeIf { it > 0 }?.let { " ($it trading ${if (it == 1) "day" else "days"})" } ?: ""
        return "$from to $to$days"
    }

    private fun returnPct(
        total: BigDecimal,
        start: BigDecimal?,
    ): String {
        if (start == null || start.signum() <= 0) return ""
        return "   (${HumanFormat.percent(total.divide(start, MathContext.DECIMAL64), signed = true)})"
    }

    private fun roundTrips(r: BacktestResult): String {
        val closed = r.trades.count { it.reducedExposure }
        if (closed == 0) return "0 closed"
        val won = r.trades.count { it.reducedExposure && it.realized.signum() > 0 }
        val pct = HumanFormat.percent(BigDecimal(won).divide(BigDecimal(closed), MathContext.DECIMAL64))
        return "$closed closed, $won won ($pct)"
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

    private fun costs(
        r: BacktestResult,
        currency: String?,
    ): String {
        val g = r.global
        val parts = mutableListOf<String>()
        if (g.commissionPaid.signum() != 0) parts += "commission ${HumanFormat.money(g.commissionPaid, currency, signed = false)}"
        if (g.swapPaid.signum() != 0) parts += "swap ${HumanFormat.money(g.swapPaid, currency, signed = false)}"
        if (g.rollCostsPaid.signum() != 0) parts += "rolls ${HumanFormat.money(g.rollCostsPaid, currency, signed = false)}"
        if (g.fundingPaid.signum() != 0) parts += "funding ${HumanFormat.money(g.fundingPaid, currency, signed = false)}"
        if (parts.isEmpty()) return "none modeled (no spread, commission or swap)"
        return parts.joinToString(", ")
    }
}
