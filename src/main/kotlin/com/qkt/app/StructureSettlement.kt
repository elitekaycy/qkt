package com.qkt.app

import com.qkt.derivatives.options.OptionPayoff
import com.qkt.events.StructureOutcome
import com.qkt.instrument.OptionTerms
import java.math.BigDecimal

/**
 * Settles every held leg whose contract has expired by [nowMs] at its intrinsic value from the
 * catalog's delivery price, the price the venue settles at. This also settles legs the venue netted
 * away (two structures long and short one contract). A leg whose delivery price is not catalogued
 * waits for it. Returns whether any leg settled.
 */
internal fun StructureBook.settleExpired(nowMs: Long): Boolean {
    if (structures.isEmpty()) return false
    val options = instruments.options() ?: return false
    return settle { leg ->
        options.deliveryPrice(leg.symbol)?.takeIf { leg.expiryMs <= nowMs }?.let { delivery ->
            val terms = requireNotNull(instruments.lookup(leg.symbol)?.derivative as? OptionTerms)
            OptionPayoff.intrinsic(terms.right, terms.strike, delivery)
        }
    }
}

/** Every held structure leg on [symbol] settles at [price], the venue's settlement price; returns whether any did. */
internal fun StructureBook.settleAt(
    symbol: String,
    price: BigDecimal,
): Boolean = settle { leg -> price.takeIf { leg.symbol == symbol } }

/** Settles each held leg at the price [priceOf] gives it, leaving legs it gives none; returns whether any settled. */
private fun StructureBook.settle(priceOf: (StructureLeg) -> BigDecimal?): Boolean {
    var settled = false
    for (structure in structures.toList()) {
        for (leg in structure.legs.filter { it.held.signum() > 0 }) {
            val price = priceOf(leg) ?: continue
            leg.realize(leg.held, price)
            structure.exit = StructureOutcome.SETTLED
            settled = true
        }
        forgetIfDone(structure)
    }
    return settled
}
