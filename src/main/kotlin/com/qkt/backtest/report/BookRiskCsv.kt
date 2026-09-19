package com.qkt.backtest.report

import com.qkt.backtest.BookRiskReport

/** The `book_risk.csv` artifact: the sampled gross/net exposure and equity series of a portfolio book. */
internal object BookRiskCsv {
    fun render(br: BookRiskReport): String {
        val sb = StringBuilder("timestamp,grossExposure,netExposure,bookEquity\n")
        for (s in br.series) {
            sb
                .append(s.timestampMs)
                .append(',')
                .append(s.grossExposure.toPlainString())
                .append(',')
                .append(s.netExposure.toPlainString())
                .append(',')
                .append(s.bookEquity.toPlainString())
                .append('\n')
        }
        return sb.toString()
    }
}
