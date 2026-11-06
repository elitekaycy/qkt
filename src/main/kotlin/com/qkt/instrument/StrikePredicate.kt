package com.qkt.instrument

import java.math.BigDecimal

/**
 * How an event contract's question reads against its bounds: [GREATER] and [GREATER_OR_EQUAL] against a floor,
 * [LESS] and [LESS_OR_EQUAL] against a cap, [BETWEEN] within both, [CUSTOM] when the question is about something other
 * than a number (a team, a candidate), [NONE] for a plain question with no strike.
 */
enum class StrikeType { GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL, BETWEEN, CUSTOM, NONE }

/**
 * The strike of an event contract: its [type] and exactly the bounds that type needs. `BETWEEN` takes both, with
 * [floor] below [cap]; a `GREATER` kind only [floor]; a `LESS` kind only [cap]; `CUSTOM` and `NONE` neither.
 *
 * ```kotlin
 * StrikePredicate(StrikeType.GREATER_OR_EQUAL, floor = BigDecimal("7425"), cap = null) // "7,425 or above"
 * ```
 */
data class StrikePredicate(
    val type: StrikeType,
    val floor: BigDecimal?,
    val cap: BigDecimal?,
) {
    init {
        val needs =
            when (type) {
                StrikeType.BETWEEN -> true to true
                StrikeType.GREATER, StrikeType.GREATER_OR_EQUAL -> true to false
                StrikeType.LESS, StrikeType.LESS_OR_EQUAL -> false to true
                StrikeType.CUSTOM, StrikeType.NONE -> false to false
            }
        require((floor != null) == needs.first && (cap != null) == needs.second) {
            "StrikePredicate $type takes ${bounds(needs)}: floor=$floor cap=$cap"
        }
        // An exact-count bucket ("exactly 45 seats") carries the same floor and cap.
        require(floor == null || cap == null || floor <= cap) {
            "StrikePredicate floor must not be above cap: $floor > $cap"
        }
    }

    private fun bounds(needs: Pair<Boolean, Boolean>): String =
        when (needs) {
            true to true -> "a floor and a cap"
            true to false -> "a floor only"
            false to true -> "a cap only"
            else -> "no bounds"
        }
}
