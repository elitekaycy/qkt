package com.qkt.backtest.report

import com.qkt.backtest.EquitySample

/**
 * The `timestamp,equity` CSV of one equity curve, and the file name a per-strategy curve is
 * written under. Colons in a strategy id are percent-encoded so the name is a single path segment.
 */
internal object EquityCsv {
    fun fileName(strategyId: String): String = "equity_${strategyId.replace(":", "%3A")}.csv"

    fun render(curve: List<EquitySample>): String {
        val sb = StringBuilder("timestamp,equity\n")
        for (s in curve) {
            sb
                .append(s.timestamp)
                .append(',')
                .append(s.equity.toPlainString())
                .append('\n')
        }
        return sb.toString()
    }
}
