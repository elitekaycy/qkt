package com.qkt.events

import com.qkt.accounting.VenueCost
import java.math.BigDecimal

/**
 * A venue settled expiring contract [symbol] at [price] per unit (an option's intrinsic value, a
 * future's delivery price), charging the account [costs]. It is not about any order: the engine
 * closes every strategy's own holding of [symbol] at [price] and shares [costs] between them by the
 * size of their holdings, so several strategies can share one account.
 */
data class ContractSettled(
    val symbol: String,
    val price: BigDecimal,
    val costs: List<VenueCost> = emptyList(),
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : BrokerEvent
