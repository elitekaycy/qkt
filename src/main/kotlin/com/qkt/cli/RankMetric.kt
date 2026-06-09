package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import java.math.BigDecimal

/** Maps a `--rank` flag to a PerformanceReport metric. Higher is better; an undefined metric ranks last. */
enum class RankMetric(
    val flag: String,
) {
    SHARPE("sharpe"),
    CALMAR("calmar"),
    PROFIT_FACTOR("profitFactor"),
    TOTAL_PNL("totalPnL"),
    WIN_RATE("winRate"),
    ;

    fun valueOf(report: PerformanceReport): BigDecimal? =
        when (this) {
            SHARPE -> report.sharpeRatio
            CALMAR -> report.calmarRatio
            PROFIT_FACTOR -> report.profitFactor
            TOTAL_PNL -> report.totalPnL
            WIN_RATE -> report.winRate
        }

    /** Ranking score: the metric, or a sentinel that sorts strictly last when undefined. */
    fun score(result: BacktestResult): BigDecimal = valueOf(result.global) ?: NULLS_LAST

    companion object {
        private val NULLS_LAST: BigDecimal = BigDecimal("-1E18")

        fun fromFlag(flag: String?): RankMetric =
            when {
                flag == null -> SHARPE
                else ->
                    entries.firstOrNull { it.flag.equals(flag, ignoreCase = true) }
                        ?: throw IllegalArgumentException(
                            "unknown --rank '$flag'; valid: ${entries.joinToString(", ") { it.flag }}",
                        )
            }
    }
}
