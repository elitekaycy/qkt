package com.qkt.backtest.report

import java.math.BigDecimal

/**
 * The `financing.csv` artifact: swap, and perpetual funding and dividends when any were paid, over the run and their
 * signed impact on net PnL.
 */
internal object FinancingCsv {
    fun render(
        swapPaid: BigDecimal,
        fundingPaid: BigDecimal = BigDecimal.ZERO,
        dividendsPaid: BigDecimal = BigDecimal.ZERO,
    ): String =
        buildString {
            append("component,paid,netPnlImpact\n")
            append("swap,${swapPaid.toPlainString()},${swapPaid.negate().toPlainString()}\n")
            if (fundingPaid.signum() !=
                0
            ) {
                append("funding,${fundingPaid.toPlainString()},${fundingPaid.negate().toPlainString()}\n")
            }
            if (dividendsPaid.signum() != 0) {
                append("dividends,${dividendsPaid.toPlainString()},${dividendsPaid.negate().toPlainString()}\n")
            }
        }
}
