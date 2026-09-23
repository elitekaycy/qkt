package com.qkt.backtest.report

import com.qkt.backtest.MonteCarloSummary

/**
 * The `monte_carlo_fan.csv` artifact: the equity percentiles across every Monte Carlo path after each
 * resampled trade, the same fan `report.html` draws. Written only when the run had a Monte Carlo.
 */
internal object MonteCarloFanCsv {
    const val FILE_NAME = "monte_carlo_fan.csv"

    fun render(mc: MonteCarloSummary): String {
        val sb = StringBuilder("tradeIndex,p5,p25,p50,p75,p95\n")
        for (p in mc.equityFanByTradeIndex) {
            sb
                .append(p.tradeIndex)
                .append(',')
                .append(p.p5.toPlainString())
                .append(',')
                .append(p.p25.toPlainString())
                .append(',')
                .append(p.p50.toPlainString())
                .append(',')
                .append(p.p75.toPlainString())
                .append(',')
                .append(p.p95.toPlainString())
                .append('\n')
        }
        return sb.toString()
    }
}
