package com.qkt.backtest.report

import com.qkt.events.RiskRejectedEvent

/** The `rejections.csv` artifact: one row per risk-rejected order request, in event order. */
internal object RejectionsCsv {
    fun render(rejections: List<RiskRejectedEvent>): String {
        val sb = StringBuilder("timestamp,reason,strategy,symbol\n")
        for (e in rejections) {
            sb
                .append(e.timestamp)
                .append(',')
                .append(csvField(e.reason))
                .append(',')
                .append(csvField(e.request.strategyId))
                .append(',')
                .append(csvField(e.request.symbol))
                .append('\n')
        }
        return sb.toString()
    }
}
