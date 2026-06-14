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

    /**
     * A ranking [score] mapped back to null when it is the undefined-ranks-last sentinel.
     * For display: an undefined metric should read as null / "n/a", never as the huge
     * negative sentinel that ranking uses. e.g. a fold whose Sharpe is undefined scores the
     * sentinel for ranking, but `defined(that)` is null so the report shows "n/a", not -1E18.
     */
    fun defined(score: BigDecimal): BigDecimal? = score.takeIf { it.compareTo(NULLS_LAST) != 0 }

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
