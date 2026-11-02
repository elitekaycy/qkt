package com.qkt.events

import java.math.BigDecimal

/**
 * A venue cost that no engine-visible fill carries: the slippage and fees of rolling a futures
 * position from one contract to the next, or a perpetual's funding, for example. The pipeline books it
 * once as realized loss for [strategyId], of [kind] ([FillAccountingKind.COST], or
 * [FillAccountingKind.FINANCING] for funding, as a backtest books it): it moves equity and risk, never
 * trade history.
 *
 * [amount] is in [symbol]'s native P&L currency and positive for a cost; a negative amount is a
 * credit (a roll filled better than its reference prices). [referencePrice] lets the accounting
 * convert through [symbol]'s own price when its currency differs from the account's.
 *
 * ```kotlin
 * bus.publish(CostIncurred("s", "BINANCE_UM:BTCUSDT@front", BigDecimal("2.5"), "roll A->B", px))
 * ```
 */
data class CostIncurred(
    val strategyId: String,
    val symbol: String,
    val amount: BigDecimal,
    val reason: String,
    val referencePrice: BigDecimal?,
    val kind: FillAccountingKind = FillAccountingKind.COST,
    /** The venue funding record this cost is a strategy's share of; null for any other cost. */
    val funding: FundingShare? = null,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : BrokerEvent

/**
 * What a [CostIncurred] that is a share of perpetual funding says of its record: the venue's [fundingId], the
 * [currency] it was charged in, when it was funded ([fundedAtMs]), and the position it was charged on ([positionId],
 * null when the holding has closed since).
 */
data class FundingShare(
    val fundingId: String,
    val currency: String,
    val fundedAtMs: Long,
    val positionId: String?,
)
