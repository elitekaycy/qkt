package com.qkt.backtest.report

import java.math.BigDecimal

/** The `financing.csv` artifact: swap and perpetual funding paid over the run and their signed impact on net PnL. */
internal object FinancingCsv {
    fun render(
        swapPaid: BigDecimal,
        fundingPaid: BigDecimal = BigDecimal.ZERO,
    ): String =
        buildString {
            append("component,paid,netPnlImpact\n")
            append("swap,${swapPaid.toPlainString()},${swapPaid.negate().toPlainString()}\n")
            append("funding,${fundingPaid.toPlainString()},${fundingPaid.negate().toPlainString()}\n")
        }
}
