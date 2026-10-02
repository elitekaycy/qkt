package com.qkt.broker.exchange

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.events.BrokerEvent
import com.qkt.instrument.InstrumentRegistry
import com.qkt.pnl.CommissionModel

/**
 * The fee [fees] charges on [fill], on the fill's own price and in the contract's currency, as the
 * [CostKind.EXCHANGE_FEE] venue cost a live exchange would report on it; empty when the fee is zero.
 */
internal fun exchangeFee(
    fees: CommissionModel,
    instruments: InstrumentRegistry,
    fill: BrokerEvent.OrderFilled,
): List<VenueCost> {
    val fee = fees.cost(fill.symbol, fill.quantity, fill.price)
    if (fee.signum() == 0) return emptyList()
    val currency = requireNotNull(instruments.lookup(fill.symbol)?.currency) { "${fill.symbol} has no currency" }
    return listOf(VenueCost(CostKind.EXCHANGE_FEE, MoneyAmount(fee, currency), fill.timestamp))
}
