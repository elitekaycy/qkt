package com.qkt.accounting.margin

import com.qkt.common.Side
import com.qkt.derivatives.options.OptionLeg
import com.qkt.derivatives.options.OptionPayoff
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * The margin option positions need. Per root and expiry it is the group's mark value less the least
 * the group can pay at expiry ([OptionPayoff.minimum]), so equity always covers the worst case:
 * - a long option needs its premium;
 * - a credit spread needs its width less its credit;
 * - a short put needs its strike less its mark (cash-secured).
 *
 * A group whose expiry payoff is unbounded below cannot be margined: a short call without an equal or
 * larger long call of the same expiry. No offset is taken across expiries, or against futures or spot.
 *
 * Pending orders may each fill or not. The requirement is convex in the quantities (a linear value
 * less a minimum of linear payoffs), so its worst case over every mix lies at a corner of each
 * symbol's range from "all its pending sells filled" to "all its pending buys filled". Groups are
 * independent, and each group's corners are checked exactly, up to [MAX_PENDING_SYMBOLS] symbols with
 * pending orders per group.
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
     * The worst-case margin of the account's options with [quantity] of [symbol] filled to [side]
     * (zero: as things stand), over every mix of pending orders filling. [mark] values each option
     * (null: refused).
     */
    fun required(
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        positions: PositionProvider,
        mark: (String) -> BigDecimal?,
    ): Outcome {
        val order = if (side == Side.BUY) quantity else quantity.negate()
        val ranges =
            (
                positions.symbols() +
                    positions.pendingEntrySymbols(
                        null,
                    ) + symbol
            ).filter(::covers).toSet().associateWith { s ->
                val base =
                    (positions.positionFor(s)?.quantity ?: BigDecimal.ZERO).add(
                        if (s ==
                            symbol
                        ) {
                            order
                        } else {
                            BigDecimal.ZERO
                        },
                    )
                listOf(
                    base.subtract(positions.pendingOrderQuantity(s, Side.SELL)),
                    base.add(positions.pendingOrderQuantity(s, Side.BUY)),
                ).distinct()
            }
        var total = BigDecimal.ZERO
        for ((group, members) in ranges.entries.groupBy { terms(it.key).let { t -> t.root to t.expiryMs } }) {
            val pending = members.count { it.value.size > 1 }
            if (pending >
                MAX_PENDING_SYMBOLS
            ) {
                return Outcome.Refused("too many pending option orders on ${group.first} to margin")
            }
            var worst = BigDecimal.ZERO
            for (corner in corners(members.map { it.key to it.value })) {
                when (val needed = groupRequirement(group, corner, mark)) {
                    is Outcome.Refused -> return needed
                    is Outcome.Required -> worst = worst.max(needed.amount)
                }
            }
            total = total.add(worst)
        }
        return Outcome.Required(total)
    }

    private fun corners(choices: List<Pair<String, List<BigDecimal>>>): List<Map<String, BigDecimal>> =
        choices.fold(listOf(emptyMap())) { acc, (s, values) ->
            acc.flatMap { partial ->
                values.map { partial + (s to it) }
            }
        }

    private fun groupRequirement(
        group: Pair<String, Long>,
        held: Map<String, BigDecimal>,
        mark: (String) -> BigDecimal?,
    ): Outcome {
        var value = BigDecimal.ZERO
        val legs = mutableListOf<OptionLeg>()
        for ((s, q) in held) {
            if (q.signum() == 0) continue
            val size = requireNotNull(instruments.lookup(s)).contractSize
            val price = mark(s) ?: return Outcome.Refused("cannot margin $s: no price")
            value = value.add(q.multiply(price).multiply(size))
            val t = terms(s)
            legs += OptionLeg(t.right, t.strike, q, size)
        }
        val least = OptionPayoff.minimum(legs) ?: return Outcome.Refused(unbounded(group))
        return Outcome.Required(value.subtract(least).max(BigDecimal.ZERO))
    }

    private fun unbounded(group: Pair<String, Long>): String =
        "options of ${group.first} expiring ${Instant.ofEpochMilli(group.second)} would have unbounded loss"

    private fun terms(symbol: String): OptionTerms =
        requireNotNull(instruments.lookup(symbol)?.derivative as? OptionTerms)

    companion object {
        /** The most symbols with pending orders in one root and expiry whose fill mixes are checked. */
        const val MAX_PENDING_SYMBOLS = 16
    }
}
