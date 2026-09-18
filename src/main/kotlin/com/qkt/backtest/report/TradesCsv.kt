package com.qkt.backtest.report

import com.qkt.backtest.TradeRecord

/**
 * The `trades.csv` artifact: one row per fill with its realized PnL in account and native
 * currency, the FX conversion used, the protective levels, and the account and strategy position
 * before and after the fill.
 */
internal object TradesCsv {
    fun render(trades: List<TradeRecord>): String {
        val sb =
            StringBuilder(
                "timestamp,strategy,symbol,side,positionEffect,orderType,quantity,price,realized,netAccountRealized," +
                    "grossAccountRealized,nativeRealized,nativeCurrency,accountRealized,accountCurrency," +
                    "fxRate,fxRateTimestamp,fxSource,riskUsd,brokerOrderId," +
                    "stopLossPrice,takeProfitPrice," +
                    "accountPositionQtyBefore,accountPositionAvgEntryBefore,accountPositionQtyAfter," +
                    "accountPositionAvgEntryAfter,strategyPositionQtyBefore,strategyPositionAvgEntryBefore," +
                    "strategyPositionQtyAfter,strategyPositionAvgEntryAfter,contractSize,fillNotional," +
                    "reducedExposure,legId,legAction\n",
            )
        for (r in trades) {
            val fillNotional = TradeAuditSummaries.fillNotional(r)
            sb
                .append(r.trade.timestamp)
                .append(',')
                .append(csvField(r.strategyId))
                .append(',')
                .append(csvField(r.trade.symbol))
                .append(',')
                .append(r.trade.side)
                .append(',')
                .append(TradeAuditSummaries.positionEffect(r))
                .append(',')
                .append(csvField(r.orderType ?: ""))
                .append(',')
                .append(r.trade.quantity.toPlainString())
                .append(',')
                .append(r.trade.price.toPlainString())
                .append(',')
                .append(r.realized.toPlainString())
                .append(',')
                .append(r.realized.toPlainString())
                .append(',')
                .append(r.accountRealized?.toPlainString() ?: "")
                .append(',')
                .append(r.nativeRealized?.toPlainString() ?: "")
                .append(',')
                .append(csvField(r.nativeCurrency ?: ""))
                .append(',')
                .append(r.accountRealized?.toPlainString() ?: "")
                .append(',')
                .append(csvField(r.accountCurrency ?: ""))
                .append(',')
                .append(r.fxRate?.toPlainString() ?: "")
                .append(',')
                .append(r.fxRateTimestamp?.toString() ?: "")
                .append(',')
                .append(csvField(r.fxSource ?: ""))
                .append(',')
                .append(r.riskUsd?.toPlainString() ?: "")
                .append(',')
                .append(csvField(r.trade.orderId))
                .append(',')
                .append(r.stopLossPrice?.toPlainString() ?: "")
                .append(',')
                .append(r.takeProfitPrice?.toPlainString() ?: "")
                .append(',')
                .append(r.accountPositionBefore?.quantity?.toPlainString() ?: "")
                .append(',')
                .append(r.accountPositionBefore?.avgEntryPrice?.toPlainString() ?: "")
                .append(',')
                .append(r.accountPositionAfter?.quantity?.toPlainString() ?: "")
                .append(',')
                .append(r.accountPositionAfter?.avgEntryPrice?.toPlainString() ?: "")
                .append(',')
                .append(r.strategyPositionBefore?.quantity?.toPlainString() ?: "")
                .append(',')
                .append(r.strategyPositionBefore?.avgEntryPrice?.toPlainString() ?: "")
                .append(',')
                .append(r.strategyPositionAfter?.quantity?.toPlainString() ?: "")
                .append(',')
                .append(r.strategyPositionAfter?.avgEntryPrice?.toPlainString() ?: "")
                .append(',')
                .append(r.contractSize?.toPlainString() ?: "")
                .append(',')
                .append(fillNotional.toPlainString())
                .append(',')
                .append(r.reducedExposure)
                .append(',')
                .append(csvField(r.legId ?: ""))
                .append(',')
                .append(r.legAction?.name ?: "")
                .append('\n')
        }
        return sb.toString()
    }
}
