package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.TradeRecord
import com.qkt.common.Money
import com.qkt.common.Side
import java.math.BigDecimal

internal data class TradeAuditSummary(
    val fills: Int,
    val buyFills: Int,
    val sellFills: Int,
    val sideAttribution: String,
    val longEntryFills: Int,
    val shortEntryFills: Int,
    val longExitFills: Int,
    val shortExitFills: Int,
    val unknownPositionFills: Int,
    val positionAttribution: String,
    val buyRealized: BigDecimal,
    val sellRealized: BigDecimal,
    val grossProfit: BigDecimal,
    val grossLoss: BigDecimal,
    val rejections: Int,
    val rejectionRate: BigDecimal?,
    val riskAuditedFills: Int,
    val minRiskUsd: BigDecimal?,
    val avgRiskUsd: BigDecimal?,
    val maxRiskUsd: BigDecimal?,
    val tradedNotional: BigDecimal,
    val maxFillNotional: BigDecimal?,
)

internal object TradeAuditSummaries {
    fun from(result: BacktestResult): TradeAuditSummary {
        val fills = result.trades.size
        val rejections = result.rejections.size
        val risks = result.trades.mapNotNull { it.riskUsd }
        val notionals = result.trades.map(::fillNotional)
        val positionEffects = result.trades.map(::positionEffect)
        val rejectionRate =
            if (fills + rejections > 0) {
                BigDecimal(rejections)
                    .divide(BigDecimal(fills + rejections), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            } else {
                null
            }
        return TradeAuditSummary(
            fills = fills,
            buyFills = result.trades.count { it.trade.side == Side.BUY },
            sellFills = result.trades.count { it.trade.side == Side.SELL },
            sideAttribution = "fill_side",
            longEntryFills = positionEffects.count { it in LONG_ENTRY_EFFECTS },
            shortEntryFills = positionEffects.count { it in SHORT_ENTRY_EFFECTS },
            longExitFills = positionEffects.count { it in LONG_EXIT_EFFECTS },
            shortExitFills = positionEffects.count { it in SHORT_EXIT_EFFECTS },
            unknownPositionFills = positionEffects.count { it == "UNKNOWN" },
            positionAttribution = "strategy_position_transition",
            buyRealized = realizedBySide(result.trades, Side.BUY),
            sellRealized = realizedBySide(result.trades, Side.SELL),
            grossProfit = grossProfit(result.trades),
            grossLoss = grossLoss(result.trades),
            rejections = rejections,
            rejectionRate = rejectionRate,
            riskAuditedFills = risks.size,
            minRiskUsd = risks.minOrNull(),
            avgRiskUsd = avgOrNull(risks),
            maxRiskUsd = risks.maxOrNull(),
            tradedNotional =
                notionals
                    .fold(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(Money.SCALE, Money.ROUNDING),
            maxFillNotional = notionals.maxOrNull()?.setScale(Money.SCALE, Money.ROUNDING),
        )
    }

    fun fillNotional(r: TradeRecord): BigDecimal {
        val contractSize = r.contractSize ?: BigDecimal.ONE
        return r.trade.price
            .multiply(r.trade.quantity.abs())
            .multiply(contractSize)
    }

    fun positionEffect(r: TradeRecord): String {
        val beforePosition = r.strategyPositionBefore
        val afterPosition = r.strategyPositionAfter
        if (beforePosition == null && afterPosition == null) return "UNKNOWN"
        val before = beforePosition?.quantity ?: BigDecimal.ZERO
        val after = afterPosition?.quantity ?: BigDecimal.ZERO
        return when {
            before.signum() == 0 && after.signum() > 0 -> "OPEN_LONG"
            before.signum() == 0 && after.signum() < 0 -> "OPEN_SHORT"
            before.signum() > 0 && after.signum() > 0 && after > before -> "INCREASE_LONG"
            before.signum() < 0 && after.signum() < 0 && after < before -> "INCREASE_SHORT"
            before.signum() > 0 && after.signum() > 0 && after < before -> "REDUCE_LONG"
            before.signum() < 0 && after.signum() < 0 && after > before -> "REDUCE_SHORT"
            before.signum() > 0 && after.signum() == 0 -> "CLOSE_LONG"
            before.signum() < 0 && after.signum() == 0 -> "CLOSE_SHORT"
            before.signum() < 0 && after.signum() > 0 -> "REVERSE_TO_LONG"
            before.signum() > 0 && after.signum() < 0 -> "REVERSE_TO_SHORT"
            else -> "UNKNOWN"
        }
    }

    private val LONG_ENTRY_EFFECTS = setOf("OPEN_LONG", "INCREASE_LONG", "REVERSE_TO_LONG")
    private val SHORT_ENTRY_EFFECTS = setOf("OPEN_SHORT", "INCREASE_SHORT", "REVERSE_TO_SHORT")
    private val LONG_EXIT_EFFECTS = setOf("REDUCE_LONG", "CLOSE_LONG", "REVERSE_TO_SHORT")
    private val SHORT_EXIT_EFFECTS = setOf("REDUCE_SHORT", "CLOSE_SHORT", "REVERSE_TO_LONG")

    private fun realizedBySide(
        trades: List<TradeRecord>,
        side: Side,
    ): BigDecimal =
        trades
            .filter { it.trade.side == side }
            .fold(BigDecimal.ZERO) { acc, r -> acc.add(r.realized) }
            .setScale(Money.SCALE, Money.ROUNDING)

    private fun grossProfit(trades: List<TradeRecord>): BigDecimal =
        trades
            .filter { it.realized.signum() > 0 }
            .fold(BigDecimal.ZERO) { acc, r -> acc.add(r.realized) }
            .setScale(Money.SCALE, Money.ROUNDING)

    private fun grossLoss(trades: List<TradeRecord>): BigDecimal =
        trades
            .filter { it.realized.signum() < 0 }
            .fold(BigDecimal.ZERO) { acc, r -> acc.add(r.realized) }
            .setScale(Money.SCALE, Money.ROUNDING)

    private fun avgOrNull(values: List<BigDecimal>): BigDecimal? =
        if (values.isEmpty()) {
            null
        } else {
            values
                .fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(values.size), Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)
        }
}
