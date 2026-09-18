package com.qkt.backtest.report

import java.math.BigDecimal

/** The `financing.csv` artifact: swap paid over the run and its signed impact on net PnL. */
internal object FinancingCsv {
    fun render(swapPaid: BigDecimal): String =
        buildString {
            append("component,paid,netPnlImpact\n")
            append("swap,")
            append(swapPaid.toPlainString())
            append(',')
            append(swapPaid.negate().toPlainString())
            append('\n')
        }
}
