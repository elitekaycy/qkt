package com.qkt.backtest.report

import com.qkt.backtest.MonthlyReturn

/** The `monthly_returns.csv` artifact: `month,return` per calendar month, consistent with `equity_daily.csv` (#1277). */
internal object MonthlyReturnsCsv {
    const val FILE_NAME = "monthly_returns.csv"

    fun render(rows: List<MonthlyReturn>): String {
        val sb = StringBuilder("month,return\n")
        for (r in rows) {
            sb
                .append(r.month)
                .append(',')
                .append(r.value.toPlainString())
                .append('\n')
        }
        return sb.toString()
    }
}
