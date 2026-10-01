package com.qkt.app

import com.qkt.accounting.MoneyAmount
import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.ExitReason
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * Settles a venue's contract-level [ContractSettled] for the strategies that share one account: each
 * strategy holding the contract closes its own position at the settlement price (a fill with exit
 * reason `EXPIRY` that is no order's, as the backtest venue settles), even when the holdings net to
 * nothing at the venue. The venue's costs are shared by the size of each holding, the last holder
 * taking the remainder so the shares sum to what the venue charged.
 */
internal class ContractSettlement(
    private val bus: EventBus,
    private val positions: StrategyPositionTracker,
) {
    /** Settle for [strategyIds], the strategies this pipeline runs. */
    fun bind(strategyIds: List<String>) {
        bus.subscribe<ContractSettled> { e -> settle(e, strategyIds) }
    }

    private fun settle(
        event: ContractSettled,
        strategyIds: List<String>,
    ) {
        val holders =
            strategyIds.mapNotNull { id ->
                positions
                    .positionFor(id, event.symbol)
                    ?.quantity
                    ?.takeIf { it.signum() != 0 }
                    ?.let { id to it }
            }
        val total = holders.fold(BigDecimal.ZERO) { sum, (_, q) -> sum.add(q.abs()) }
        val shares = holders.map { (_, q) -> q.abs() }
        for ((index, holder) in holders.withIndex()) {
            val (strategyId, quantity) = holder
            val costs = event.costs.map { cost -> cost.copy(amount = shareOf(cost.amount, shares, index, total)) }
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = "settle:${event.symbol}:$strategyId",
                    brokerOrderId = null,
                    symbol = event.symbol,
                    side = if (quantity.signum() > 0) Side.SELL else Side.BUY,
                    price = event.price,
                    quantity = quantity.abs(),
                    strategyId = strategyId,
                    timestamp = event.timestamp,
                    updatesOrderExecution = false,
                    typedVenueCosts = costs,
                    exitReason = ExitReason.EXPIRY,
                ),
            )
        }
    }

    /** Holder [index]'s share of [amount] by [shares] of [total]; the last holder takes what the others left. */
    private fun shareOf(
        amount: MoneyAmount,
        shares: List<BigDecimal>,
        index: Int,
        total: BigDecimal,
    ): MoneyAmount {
        val part = { i: Int -> amount.amount.multiply(shares[i]).divide(total, Money.CONTEXT) }
        val value =
            if (index < shares.lastIndex) {
                part(index)
            } else {
                amount.amount.subtract((0 until index).fold(BigDecimal.ZERO) { sum, i -> sum.add(part(i)) })
            }
        return MoneyAmount(value, amount.currency)
    }
}
