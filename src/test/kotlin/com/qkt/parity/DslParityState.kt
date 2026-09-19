package com.qkt.parity

import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.Trade
import java.math.BigDecimal

/** The comparable, string-normalized state a parity run captures from the backtest and from the live session. */
internal object DslParityState {
    data class TradeState(
        val strategyId: String,
        val orderId: String,
        val symbol: String,
        val side: String,
        val quantity: String,
        val price: String,
        val timestamp: Long,
        val realized: String,
    )

    data class PositionState(
        val symbol: String,
        val quantity: String,
        val avgEntryPrice: String,
        val openedAt: Long?,
    )

    data class PnlState(
        val realized: String,
        val unrealized: String,
        val total: String,
    )

    data class HaltState(
        val reason: String,
        val strategyId: String?,
        val timestamp: Long,
    )

    data class RejectionState(
        val strategyId: String,
        val symbol: String,
        val side: String,
        val quantity: String,
        val reason: String,
        val timestamp: Long,
    )

    data class Snapshot(
        val trades: List<TradeState>,
        val positions: List<PositionState>,
        val pnl: PnlState,
        val rejections: List<RejectionState>,
        val halts: List<HaltState>,
    )

    fun tradeState(
        strategyId: String,
        trade: Trade,
        realized: BigDecimal,
    ): TradeState =
        TradeState(
            strategyId = strategyId,
            orderId = trade.orderId,
            symbol = trade.symbol,
            side = trade.side.name,
            quantity = number(trade.quantity),
            price = number(trade.price),
            timestamp = trade.timestamp,
            realized = number(realized),
        )

    fun positionState(position: com.qkt.positions.Position): PositionState =
        PositionState(
            symbol = position.symbol,
            quantity = number(position.quantity),
            avgEntryPrice = number(position.avgEntryPrice),
            openedAt = position.openedAt,
        )

    fun rejectionState(event: RiskRejectedEvent): RejectionState =
        RejectionState(
            strategyId = event.request.strategyId,
            symbol = event.request.symbol,
            side = event.request.side.name,
            quantity = number(event.request.quantity),
            reason = event.reason,
            timestamp = event.timestamp,
        )

    fun number(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
