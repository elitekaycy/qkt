package com.qkt.events

import java.math.BigDecimal

/**
 * A venue cost that no engine-visible fill carries: the slippage and fees of rolling a futures
 * position from one contract to the next, for example. The pipeline books it once as realized loss
 * for [strategyId] (kind [FillAccountingKind.COST]): it moves equity and risk, never trade history.
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
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : BrokerEvent
