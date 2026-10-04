package com.qkt.broker.liquidation

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import java.math.BigDecimal

/**
 * One position a backtest venue liquidated: [strategyId]'s [quantity] of [symbol] closed on [side] at
 * [price], paying [fee] (both in the contract's currency), because account [equity] had fallen below
 * the [maintenance] margin of its margined positions (both in account currency).
 */
data class Liquidation(
    val atMs: Long,
    val strategyId: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val fee: BigDecimal,
    val equity: BigDecimal,
    val maintenance: BigDecimal,
)

/** Every liquidation of a run, in order; the source of `liquidations.csv`. */
class LiquidationLog {
    private val recorded = mutableListOf<Liquidation>()

    /** Record [liquidation]. */
    fun record(liquidation: Liquidation) {
        recorded += liquidation
    }

    /** The liquidations recorded so far, oldest first. */
    val entries: List<Liquidation> get() = recorded.toList()
}

/**
 * The venue close liquidating [strategyId]'s [net] position (positive long) on [symbol] at [price] at
 * [atMs]: a fill that is no order's (`updatesOrderExecution = false`) with exit reason
 * [ExitReason.LIQUIDATION]. The venue adds its fee.
 */
fun liquidationFill(
    symbol: String,
    strategyId: String,
    net: BigDecimal,
    price: BigDecimal,
    atMs: Long,
): BrokerEvent.OrderFilled {
    val id = "liquidation:$symbol:$strategyId:$atMs"
    return BrokerEvent.OrderFilled(
        clientOrderId = id,
        brokerOrderId = id,
        symbol = symbol,
        side = if (net.signum() > 0) Side.SELL else Side.BUY,
        price = price.setScale(Money.SCALE, Money.ROUNDING),
        quantity = net.abs(),
        strategyId = strategyId,
        timestamp = atMs,
        updatesOrderExecution = false,
        exitReason = ExitReason.LIQUIDATION,
    )
}

/** Why a venue cancels a working order on [symbol] when it liquidates the contract. */
fun liquidationReason(symbol: String): String = "$symbol liquidated: account equity fell below maintenance margin"
