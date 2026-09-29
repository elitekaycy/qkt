package com.qkt.backtest.report

import com.qkt.backtest.DailyEquity

/** The `equity_daily.csv` artifact: `date,open,high,low,close` of account equity per UTC day (#1277). */
internal object DailyEquityCsv {
    const val FILE_NAME = "equity_daily.csv"

    fun render(rows: List<DailyEquity>): String {
        val sb = StringBuilder("date,open,high,low,close\n")
        for (r in rows) {
            sb
                .append(r.date)
                .append(',')
                .append(r.open.toPlainString())
                .append(',')
                .append(r.high.toPlainString())
                .append(',')
                .append(r.low.toPlainString())
                .append(',')
                .append(r.close.toPlainString())
                .append('\n')
        }
        return sb.toString()
    }
}
