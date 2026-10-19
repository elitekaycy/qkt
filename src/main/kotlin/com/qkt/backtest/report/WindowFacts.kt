package com.qkt.backtest.report

import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.WindowReport
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The phrases the console summary, `--verbose` and report.html share for a `--oos-split` or
 * `--metrics-window` window (#1451), so every output states a window the same way.
 */
object WindowFacts {
    /** `In sample` / `Out of sample` for the `--oos-split` pair, else the window's own name. */
    fun label(w: WindowReport): String =
        when (w.window.name) {
            "in_sample" -> "In sample"
            "out_of_sample" -> "Out of sample"
            else -> w.window.name
        }

    /** The window's UTC days, first to last: `[from, to)` shown as `2018-01-01 to 2023-12-31`. */
    fun dates(w: WindowReport): String {
        val from = LocalDate.ofInstant(Instant.ofEpochMilli(w.window.fromMs), ZoneOffset.UTC)
        val to = LocalDate.ofInstant(Instant.ofEpochMilli(w.window.toMs - 1), ZoneOffset.UTC)
        return "$from to $to"
    }

    /** `   (-19.9%)`: the equity change as a share of the window's starting equity, or empty without one. */
    fun returnPct(w: WindowReport): String {
        if (w.equityStart.signum() <= 0) return ""
        val change = w.equityEnd.subtract(w.equityStart).divide(w.equityStart, MathContext.DECIMAL64)
        return "   (${HumanFormat.percent(change, signed = true)})"
    }

    /** `188 round trips, 30.1% won`, naming break-even ones the win rate leaves out. */
    fun roundTrips(g: PerformanceReport): String {
        val n = g.roundTrips
        val noun = if (n == 1) "round trip" else "round trips"
        if (n == 0) return "0 round trips"
        val even = if (g.roundTripsBreakEven > 0) ", ${g.roundTripsBreakEven} break-even" else ""
        return "$n $noun, ${HumanFormat.percent(g.winRate)} won$even"
    }

    /** `commission 190.96 USD, swap 208.67 USD`: every cost incurred, or `none`. */
    fun costs(
        g: PerformanceReport,
        currency: String?,
    ): String = ExecutionFactsText.incurred(g, currency).ifEmpty { listOf("none") }.joinToString(", ")
}
