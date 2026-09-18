package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.TradeRecord
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The `pnl_components.csv` artifact: per UTC day, the realized PnL of that day's fills, the
 * reported daily PnL, and the adjustment between them, for the whole book and then each strategy.
 */
internal object PnlComponentsCsv {
    fun render(result: BacktestResult): String {
        val sb = StringBuilder("scope,strategy,date,tradeRealized,adjustment,dailyPnL\n")
        appendPnlComponents(sb, scope = "global", strategyId = "", report = result.global, trades = result.trades)
        for ((strategyId, report) in result.perStrategy.entries.sortedBy { it.key }) {
            appendPnlComponents(
                sb,
                scope = "strategy",
                strategyId = strategyId,
                report = report,
                trades = result.trades.filter { it.strategyId == strategyId },
            )
        }
        return sb.toString()
    }

    private fun appendPnlComponents(
        sb: StringBuilder,
        scope: String,
        strategyId: String,
        report: PerformanceReport,
        trades: List<TradeRecord>,
    ) {
        val tradeDaily = trades.realizedByUtcDate()
        val dates = (tradeDaily.keys + report.dailyPnL.keys).toSortedSet()
        for (date in dates) {
            val tradeRealized = tradeDaily[date] ?: BigDecimal.ZERO
            val dailyPnl = report.dailyPnL[date] ?: BigDecimal.ZERO
            val adjustment = dailyPnl.subtract(tradeRealized)
            sb
                .append(scope)
                .append(',')
                .append(csvField(strategyId))
                .append(',')
                .append(date)
                .append(',')
                .append(tradeRealized.toPlainString())
                .append(',')
                .append(adjustment.toPlainString())
                .append(',')
                .append(dailyPnl.toPlainString())
                .append('\n')
        }
    }

    private fun List<TradeRecord>.realizedByUtcDate(): Map<LocalDate, BigDecimal> =
        groupBy {
            Instant
                .ofEpochMilli(it.trade.timestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
        }.mapValues { (_, trades) ->
            trades.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.realized) }
        }
}
