package com.qkt.broker.continuous

import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.marketdata.Tick

/** The latest quotes a roll's legs would trade against, on the old contract and the new; null when none was heard. */
internal data class RollQuotes(
    val from: Tick?,
    val to: Tick?,
) {
    companion object {
        val NONE = RollQuotes(null, null)
    }
}

/**
 * The quotes of one stream's contracts, in contract prices. When [judging] (a live lane), a roll asks [forRoll] for
 * what its legs would hit: the last quote of the old contract, and the new one's in the tick that started the roll,
 * since by then the stream serves the new contract.
 */
internal class LaneQuotes(
    private val chainOf: () -> ContinuousChain,
    private val space: (Int) -> PriceSpace,
    private val judging: Boolean,
) {
    private var last: Tick? = null

    /** [tick] of the stream as a tick of contract [index], remembered as that contract's last quote. */
    fun heard(
        tick: Tick,
        index: Int,
    ): Tick = contractTick(tick, index).also { last = it }

    /** The quotes of a roll from contract [from] to [to]; [tick] started it, null when an order did. */
    fun forRoll(
        from: Int,
        to: Int,
        tick: Tick?,
    ): RollQuotes =
        if (judging) {
            RollQuotes(last?.takeIf { it.symbol == chainOf().contractSymbol(from) }, tick?.let { contractTick(it, to) })
        } else {
            RollQuotes.NONE
        }

    private fun contractTick(
        tick: Tick,
        index: Int,
    ): Tick {
        val prices = space(index)
        return tick.copy(
            symbol = chainOf().contractSymbol(index),
            price = prices.priceToContract(tick.price),
            bid = tick.bid?.let(prices::priceToContract),
            ask = tick.ask?.let(prices::priceToContract),
        )
    }
}
