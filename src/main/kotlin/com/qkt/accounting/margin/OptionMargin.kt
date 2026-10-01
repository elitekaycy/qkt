package com.qkt.accounting.margin

import com.qkt.common.Side
import com.qkt.derivatives.options.OptionLeg
import com.qkt.derivatives.options.OptionPayoff
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

/**
 * The margin option positions need: per root and expiry, the group's current mark value less the
 * least it can pay at expiry ([OptionPayoff.minimum]), so equity always covers the worst case. A long
 * option needs its premium, a credit spread its width less its credit, a short put its strike less
 * its mark (cash-secured). A group whose expiry payoff is unbounded below (a short call without an
 * equal or larger long call of the same expiry) cannot be margined. No offset is taken across
 * expiries or against futures or spot.
 */
class OptionMargin(
    private val instruments: InstrumentRegistry,
) {
    /** What [required] found: an amount, or why the positions cannot be margined. */
    sealed interface Outcome {
        /** Equity must cover [amount]. */
        data class Required(
            val amount: BigDecimal,
        ) : Outcome

        /** The positions cannot be margined, for [reason]. */
        data class Refused(
            val reason: String,
        ) : Outcome
    }

    /** Whether [symbol] is an option this model margins. */
    fun covers(symbol: String): Boolean = instruments.lookup(symbol)?.derivative is OptionTerms

    /**
     * The margin of the account's options after an order of [quantity] on [symbol] to [side], in the
     * worst of four outcomes: alone, with every pending buy, every pending sell, or both filled. [mark]
     * prices each option (null: refused).
     */
    fun required(
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        positions: PositionProvider,
        mark: (String) -> BigDecimal?,
    ): Outcome {
        val symbols = (positions.symbols() + positions.pendingEntrySymbols(null) + symbol).filter(::covers).toSet()
        val order = if (side == Side.BUY) quantity else quantity.negate()
        var worst = BigDecimal.ZERO
        for ((withBuys, withSells) in listOf(false to false, true to false, false to true, true to true)) {
            val held =
                symbols.associateWith { s ->
                    var q = positions.positionFor(s)?.quantity ?: BigDecimal.ZERO
                    if (s == symbol) q = q.add(order)
                    if (withBuys) q = q.add(positions.pendingOrderQuantity(s, Side.BUY))
                    if (withSells) q = q.subtract(positions.pendingOrderQuantity(s, Side.SELL))
                    q
                }
            when (val outcome = groups(held, mark)) {
                is Outcome.Refused -> return outcome
                is Outcome.Required -> worst = worst.max(outcome.amount)
            }
        }
        return Outcome.Required(worst)
    }

    private fun groups(
        held: Map<String, BigDecimal>,
        mark: (String) -> BigDecimal?,
    ): Outcome {
        var total = BigDecimal.ZERO
        val byExpiry =
            held.filterValues { it.signum() != 0 }.entries.groupBy { (s, _) ->
                terms(s).let {
                    it.root to
                        it.expiryMs
                }
            }
        for ((group, members) in byExpiry) {
            var value = BigDecimal.ZERO
            val legs =
                members.map { (s, q) ->
                    val size = requireNotNull(instruments.lookup(s)).contractSize
                    val price = mark(s) ?: return Outcome.Refused("cannot margin $s: no price")
                    value = value.add(q.multiply(price).multiply(size))
                    terms(s).let { OptionLeg(it.right, it.strike, q, size) }
                }
            val worst =
                OptionPayoff.minimum(legs)
                    ?: return Outcome.Refused(
                        "options of ${group.first} expiring ${java.time.Instant.ofEpochMilli(
                            group.second,
                        )} have unbounded loss",
                    )
            total = total.add(value.subtract(worst).max(BigDecimal.ZERO))
        }
        return Outcome.Required(total)
    }

    private fun terms(symbol: String): OptionTerms =
        requireNotNull(instruments.lookup(symbol)?.derivative as? OptionTerms)
}
