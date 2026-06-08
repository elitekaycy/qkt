package com.qkt.pnl

import com.qkt.common.Money
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Charges and tallies trading commission across a run.
 *
 * The trading pipeline calls [charge] on every fill; the returned amount is subtracted from
 * realized PnL so equity, drawdown, and Sharpe reflect the cost, while [total]/[totalFor]
 * feed the report's `commissionPaid` line — the explicit bridge from gross trade PnL to net.
 *
 * Defaults to [NoCommission], so a book that is never given a real model (live, or a backtest
 * with no configured rate) charges nothing and tallies zero.
 */
class CommissionBook(
    private val model: CommissionModel = NoCommission,
) {
    private val byStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    @Volatile
    private var total: BigDecimal = Money.ZERO

    /**
     * Commission for filling [quantity] lots of [symbol] under [strategyId]: tallies it and
     * returns the amount so the caller can deduct it from that fill's realized PnL.
     */
    fun charge(
        strategyId: String,
        symbol: String,
        quantity: BigDecimal,
    ): BigDecimal {
        val cost = model.cost(symbol, quantity).setScale(Money.SCALE, Money.ROUNDING)
        if (cost.signum() == 0) return Money.ZERO
        total = total.add(cost)
        if (strategyId.isNotBlank()) {
            byStrategy[strategyId] = (byStrategy[strategyId] ?: Money.ZERO).add(cost)
        }
        return cost
    }

    /** Total commission charged across all strategies. */
    fun total(): BigDecimal = total

    /** Commission charged under [strategyId]. */
    fun totalFor(strategyId: String): BigDecimal = byStrategy[strategyId] ?: Money.ZERO
}
