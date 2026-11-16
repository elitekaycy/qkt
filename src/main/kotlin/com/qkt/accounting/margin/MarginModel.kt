package com.qkt.accounting.margin

import com.qkt.accounting.AccountingEngine
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.MarginBasis
import com.qkt.instrument.MarginTerms
import java.math.BigDecimal

/**
 * Initial and maintenance margin of a position, in account currency, from its instrument's
 * [MarginTerms]: `|quantity| × amount` per contract, or `|quantity| × price × contractSize × rate`
 * on notional, converted from the instrument's currency. Instruments without terms need none. A
 * continuous stream's price is in its adjusted series; notional margin is taken on the contract the
 * stream holds at that time, which can sit far from the series after many rolls.
 *
 * An event contract ([EventTerms]) is fully collateralised instead: opening it needs the premium at stake, `qty × p`
 * for a YES (long) position and `qty × (payout − p)` for a NO (short) one. It has no maintenance level: a paid-up
 * position is never in a margin call.
 */
class MarginModel(
    private val instruments: InstrumentRegistry,
    private val accounting: AccountingEngine,
) {
    private val chains: ContinuousChains? by lazy { instruments.futures()?.let(::ContinuousChains) }

    /** Whether [symbol] carries margin terms. */
    fun hasTerms(symbol: String): Boolean = terms(symbol) != null

    /** Whether opening [symbol] needs equity behind it: it carries margin terms or is an event contract. */
    fun requiresInitial(symbol: String): Boolean = hasTerms(symbol) || isEvent(symbol)

    /** Whether [symbol] is an event contract, whose [initial] margin depends on the side held. */
    fun isEvent(symbol: String): Boolean = instruments.lookup(symbol)?.derivative is EventTerms

    /**
     * Margin to open or hold [quantity] of [symbol] at [price]. Only an event contract reads the sign of [quantity]
     * (positive holds YES, negative NO); every other kind takes its size.
     */
    fun initial(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
        timestamp: Long,
    ): BigDecimal {
        val meta = instruments.lookup(symbol)
        val event = meta?.derivative as? EventTerms
        if (meta == null || event == null) return margin(symbol, quantity, price, timestamp) { it.initial }
        val perContract = if (quantity.signum() >= 0) price else event.payout.subtract(price).max(BigDecimal.ZERO)
        val native = quantity.abs().multiply(meta.contractSize).multiply(perContract)
        return accounting.convertNotional(symbol, native, timestamp, price).account.amount
    }

    /** Margin below which [quantity] of [symbol] at [price] is in a margin call. */
    fun maintenance(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
        timestamp: Long,
    ): BigDecimal = margin(symbol, quantity, price, timestamp) { it.maintenance }

    /** [price] of [symbol] as the price of the contract it holds at [timestamp]; unchanged for anything but a continuous stream. */
    private fun contractPrice(
        symbol: String,
        price: BigDecimal,
        timestamp: Long,
    ): BigDecimal {
        val directory = instruments.futures() ?: return price
        if (directory.rootOfContinuous(symbol) == null) return price
        val chain =
            try {
                chains?.chainFor(symbol)
            } catch (e: IllegalArgumentException) {
                null
            } ?: return price
        val index = chain.indexAt(timestamp)?.takeIf(chain::covers) ?: return price
        return chain.spaceFor(index).priceToContract(price)
    }

    private fun terms(symbol: String): MarginTerms? = instruments.lookup(symbol)?.derivative?.margin

    private fun margin(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
        timestamp: Long,
        figure: (MarginTerms) -> BigDecimal,
    ): BigDecimal {
        val meta = instruments.lookup(symbol) ?: return BigDecimal.ZERO
        val terms = meta.derivative?.margin ?: return BigDecimal.ZERO
        val native =
            when (terms.basis) {
                MarginBasis.PER_CONTRACT -> quantity.abs().multiply(figure(terms))
                MarginBasis.NOTIONAL -> {
                    val notional = quantity.abs().multiply(contractPrice(symbol, price, timestamp))
                    notional.multiply(meta.contractSize).multiply(figure(terms))
                }
            }
        return accounting.convertNotional(symbol, native, timestamp, price).account.amount
    }
}
