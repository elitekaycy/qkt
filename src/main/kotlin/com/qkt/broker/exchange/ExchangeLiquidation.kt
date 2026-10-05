package com.qkt.broker.exchange

import com.qkt.broker.liquidation.liquidationFill
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.pnl.CommissionModel

/**
 * Liquidation on [ExchangeSimulator]: every strategy's net position on a contract, as the exchange
 * holds it in [settlement], closed at the contract's executable price in [prices] (no slippage) with
 * the fee [fees] charges a fill, published on [bus] and applied to [settlement].
 */
internal class ExchangeLiquidation(
    private val bus: EventBus,
    private val clock: Clock,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry,
    private val fees: CommissionModel,
    private val settlement: ExpirySettlement,
) {
    /** Close every position on [symbol]; a side the contract has no price for is left open. */
    fun close(symbol: String) {
        for ((strategyId, net) in settlement.holdersOf(symbol)) {
            val price = prices.executionPrice(symbol, if (net.signum() > 0) Side.SELL else Side.BUY) ?: continue
            val fill = liquidationFill(symbol, strategyId, net, price, clock.now())
            val charged = fill.copy(typedVenueCosts = exchangeFee(fees, instruments, fill))
            settlement.onFill(charged)
            bus.publish(charged)
        }
    }
}
