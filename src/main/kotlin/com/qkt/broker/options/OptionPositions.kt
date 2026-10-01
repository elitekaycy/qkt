package com.qkt.broker.options

import com.qkt.common.Side
import java.math.BigDecimal

/** Net option positions per strategy and contract, from the venue's own fills. */
internal class OptionPositions {
    private val net = HashMap<Pair<String, String>, BigDecimal>()

    /** The net quantity [strategyId] holds of [symbol]; positive is long. */
    fun of(
        strategyId: String,
        symbol: String,
    ): BigDecimal = net[strategyId to symbol] ?: BigDecimal.ZERO

    /** Books a fill of [quantity] on [side]. */
    fun apply(
        strategyId: String,
        symbol: String,
        side: Side,
        quantity: BigDecimal,
    ) {
        val key = strategyId to symbol
        val next = of(strategyId, symbol).add(if (side == Side.BUY) quantity else quantity.negate())
        if (next.signum() == 0) net.remove(key) else net[key] = next
    }

    /** The contracts anyone holds. */
    fun symbols(): Set<String> = net.keys.mapTo(sortedSetOf()) { it.second }

    /** Every non-zero position of [symbol], by strategy. */
    fun holdersOf(symbol: String): Map<String, BigDecimal> =
        net.filterKeys { it.second == symbol }.mapKeys { it.key.first }

    /** Drops every position of [symbol] (after it settled). */
    fun clear(symbol: String) {
        net.keys.removeIf { it.second == symbol }
    }
}
