package com.qkt.backtest

import java.math.BigDecimal

/**
 * Online worst-daily-drawdown accumulator: for each UTC day, `(open - min) / open`, the largest across all days.
 * Days are the rows of `equity_daily.csv` ([DailyEquityAccumulator]), so a day that a midnight sample began opens
 * at that sample and a day with a single sample (daily bars) still measures its fall (#1450). Constant memory.
 *
 * e.g. a day opens at 100 and dips to 90 -> 0.10 for that day; the worst day wins overall.
 */
class DailyDrawdownAccumulator {
    private val days = DailyEquityAccumulator(keepRows = false)

    fun accept(
        timestamp: Long,
        equity: BigDecimal,
    ) = days.accept(timestamp, equity)

    /** Worst daily drawdown across all days seen so far, scaled to money precision. */
    fun maxDailyDrawdown(): BigDecimal = days.maxDailyDrawdown()

    companion object {
        /** One-pass worst daily drawdown over a full equity curve. */
        fun fromCurve(samples: List<EquitySample>): BigDecimal {
            val acc = DailyDrawdownAccumulator()
            samples.forEach { acc.accept(it.timestamp, it.equity) }
            return acc.maxDailyDrawdown()
        }
    }
}
