package com.qkt.cli.sweep

import com.qkt.backtest.TradeRecord
import com.qkt.backtest.report.ReportSerializer
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Aggregate the linear inputs needed to estimate paper-fill spread and slippage and reconcile
 * the separately emitted commission without an unbounded fill tape. Grouping by UTC day
 * preserves daily Sharpe and drawdown reconstruction; grouping by symbol preserves each
 * instrument's cost model.
 */
internal fun fillCostSummaryJson(trades: List<TradeRecord>): String {
    val totals = mutableMapOf<FillCostKey, FillCostTotal>()
    for (record in trades) {
        val trade = record.trade
        val day =
            Instant
                .ofEpochMilli(trade.timestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString()
        val key = FillCostKey(day, trade.symbol)
        val quantityAbs = trade.quantity.abs()
        val contractSize = record.contractSize ?: BigDecimal.ONE
        val total = totals.getOrPut(key) { FillCostTotal() }
        total.fills += 1
        total.lotsAbs = total.lotsAbs.add(quantityAbs)
        total.notionalAbs =
            total.notionalAbs.add(
                trade.price
                    .abs()
                    .multiply(quantityAbs)
                    .multiply(contractSize),
            )
    }
    return totals.entries
        .sortedWith(compareBy({ it.key.day }, { it.key.symbol }))
        .joinToString(prefix = "[", postfix = "]", separator = ",") { (key, total) ->
            """{"day":${ReportSerializer.jsonString(key.day)},""" +
                """"symbol":${ReportSerializer.jsonString(key.symbol)},""" +
                """"fills":${total.fills},"lotsAbs":${total.lotsAbs.toPlainString()},""" +
                """"notionalAbs":${total.notionalAbs.toPlainString()}}"""
        }
}

private data class FillCostKey(
    val day: String,
    val symbol: String,
)

private data class FillCostTotal(
    var fills: Int = 0,
    var lotsAbs: BigDecimal = BigDecimal.ZERO,
    var notionalAbs: BigDecimal = BigDecimal.ZERO,
)
