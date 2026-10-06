package com.qkt.broker

import com.qkt.accounting.MoneyAmount
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.ExitReason
import java.math.BigDecimal

/**
 * This settlement as the venue close of each of [holders] (strategy id to its signed holding of the
 * contract, flat ones skipped): a fill at the settlement price with exit reason `EXPIRY` that is no
 * order's, the venue's costs shared by the size of each holding, the last holder taking the remainder so
 * the shares sum to what the venue charged. E.g. holdings +0.2 and -0.1 with a 3 USDC fee close as a
 * SELL 0.2 charged 2 and a BUY 0.1 charged 1.
 */
fun ContractSettled.closesFor(holders: List<Pair<String, BigDecimal>>): List<BrokerEvent.OrderFilled> {
    val held = holders.filter { it.second.signum() != 0 }
    val shares = held.map { it.second.abs() }
    val total = shares.fold(BigDecimal.ZERO, BigDecimal::add)
    return held.mapIndexed { index, (strategyId, quantity) ->
        BrokerEvent.OrderFilled(
            clientOrderId = "settle:$symbol:$strategyId",
            brokerOrderId = null,
            symbol = symbol,
            side = if (quantity.signum() > 0) Side.SELL else Side.BUY,
            price = price,
            quantity = quantity.abs(),
            strategyId = strategyId,
            timestamp = timestamp,
            updatesOrderExecution = false,
            typedVenueCosts = costs.map { it.copy(amount = shareOf(it.amount, shares, index, total)) },
            exitReason = ExitReason.EXPIRY,
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
