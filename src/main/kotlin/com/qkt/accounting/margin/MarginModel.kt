package com.qkt.accounting.margin

import com.qkt.accounting.AccountingEngine
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.MarginBasis
import com.qkt.instrument.MarginTerms
import java.math.BigDecimal

/**
 * Initial and maintenance margin of a position, in account currency, from its instrument's
 * [MarginTerms]: `|quantity| × amount` per contract, or `|quantity| × price × contractSize × rate`
 * on notional, converted from the instrument's currency. Instruments without terms need none.
 */
class MarginModel(
    private val instruments: InstrumentRegistry,
    private val accounting: AccountingEngine,
) {
    /** Whether [symbol] carries margin terms. */
    fun hasTerms(symbol: String): Boolean = terms(symbol) != null

    /** Margin to open or hold [quantity] of [symbol] at [price]. */
    fun initial(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
        timestamp: Long,
    ): BigDecimal = margin(symbol, quantity, price, timestamp) { it.initial }

    /** Margin below which [quantity] of [symbol] at [price] is in a margin call. */
    fun maintenance(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
        timestamp: Long,
    ): BigDecimal = margin(symbol, quantity, price, timestamp) { it.maintenance }

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
                MarginBasis.NOTIONAL ->
                    quantity
                        .abs()
                        .multiply(
                            price,
                        ).multiply(meta.contractSize)
                        .multiply(figure(terms))
            }
        return accounting.convertNotional(symbol, native, timestamp, price).account.amount
    }
}
