package com.qkt.broker.options

import com.qkt.broker.liquidation.liquidationFill
import com.qkt.bus.EventBus
import com.qkt.common.Side
import com.qkt.derivatives.options.chain.ChainQuoteLookup
import com.qkt.derivatives.options.chain.OptionQuotes
import com.qkt.instrument.InstrumentRegistry

/**
 * Liquidation on [OptionExchange]: every strategy's net position on a contract, as the venue holds it
 * in [positions], closed at the contract's quote in the chain snapshot of the moment (a long at the
 * bid, a short at the ask) with its [OptionFee], published on [bus]. Without a quote of that side at
 * that moment the position stays open for a later call.
 */
internal class OptionLiquidation(
    private val bus: EventBus,
    private val instruments: InstrumentRegistry,
    private val quotes: ChainQuoteLookup,
    private val positions: OptionPositions,
) {
    /** Close every position on [symbol] at [atMs]. */
    fun close(
        symbol: String,
        atMs: Long,
    ) {
        val holders = positions.holdersOf(symbol).toSortedMap()
        if (holders.isEmpty()) return
        val root = instruments.options()?.optionRoot(symbol) ?: return
        val quote = quotes.quoteAt(symbol, atMs) ?: return
        val sides = OptionQuotes.sides(quote, root)
        for ((strategyId, net) in holders) {
            val side = if (net.signum() > 0) Side.SELL else Side.BUY
            val price = (if (side == Side.BUY) sides.ask else sides.bid) ?: continue
            // The venue charges on the index; a series that did not record it falls back to the forward.
            val fee = OptionFee.trade(root, net, price, quote.index ?: quote.underlying)
            positions.apply(strategyId, symbol, side, net.abs())
            bus.publish(
                liquidationFill(symbol, strategyId, net, price, atMs).copy(
                    typedVenueCosts = OptionFee.costs(fee, root, atMs),
                ),
            )
        }
    }
}
