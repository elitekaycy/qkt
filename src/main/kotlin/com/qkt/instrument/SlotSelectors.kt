package com.qkt.instrument

import java.math.BigDecimal

/** One contract of an event as a strike selector sees it: its qkt [symbol] and its YES [mid] price. */
data class SlotCandidate(
    val symbol: String,
    val mid: BigDecimal,
)

/** How a strike selector picks one contract from an event's two-sided quoted contracts, given in strike order. */
fun interface StrikeRule {
    /** The contract picked from [candidates] (strike order, lowest first), or null when none fits. */
    fun pick(candidates: List<SlotCandidate>): SlotCandidate?
}

/**
 * The selectors an event slot (`VENUE:FAMILY@<event>.<strike>`) is spelled with, in one table so a new selector is one
 * entry here plus its row in `docs/reference/dsl/streams.md`.
 *
 * Events: `front` is the open event closing first, `next` the one after it. Strikes, over the event's contracts with a
 * usable two-sided quote:
 * - `pNN` (`p05` … `p95`): the YES mid nearest NN %, ties to the lower strike;
 * - `atm`: `p50`;
 * - `aboveN` / `belowN`: N strikes above / below `atm` in strike order;
 * - `topN` (`top1` …): the Nth highest YES mid, ties to the lower strike.
 */
object SlotSelectors {
    private class Entry(
        val spelling: String,
        val pattern: Regex,
        val rule: (Int) -> StrikeRule?,
    )

    private val events: Map<String, Int> = linkedMapOf("front" to 0, "next" to 1)

    private val strikes: List<Entry> =
        listOf(
            Entry("pNN (p05 ... p95)", Regex("p([0-9]{2})")) { n -> if (n in 5..95) nearest(n) else null },
            Entry("atm", Regex("atm()")) { nearest(ATM) },
            Entry("aboveN", Regex("above([1-9][0-9]*)")) { n -> fromAtm(n) },
            Entry("belowN", Regex("below([1-9][0-9]*)")) { n -> fromAtm(-n) },
            Entry("topN", Regex("top([1-9][0-9]*)")) { n -> top(n) },
        )

    /** How many open events after the front one event selector [token] skips (`front` 0, `next` 1), or null. */
    fun eventOffset(token: String): Int? = events[token]

    /** The rule strike selector [token] picks with (`p70`, `atm`, `above2`, `top1`), or null when it is none. */
    fun strike(token: String): StrikeRule? {
        for (entry in strikes) {
            val match = entry.pattern.matchEntire(token) ?: continue
            return entry.rule(match.groupValues[1].toIntOrNull() ?: 0)
        }
        return null
    }

    /** The event selector spellings, for error messages. */
    val eventSpellings: String get() = events.keys.joinToString { "@$it" }

    /** The strike selector spellings, for error messages. */
    val strikeSpellings: String get() = strikes.joinToString { it.spelling }

    private const val ATM = 50

    private fun nearest(percent: Int): StrikeRule {
        val target = BigDecimal(percent).movePointLeft(2)
        // minWithOrNull keeps the first of equal elements, and candidates come lowest strike first.
        return StrikeRule { candidates -> candidates.minWithOrNull(compareBy { it.mid.subtract(target).abs() }) }
    }

    private fun fromAtm(steps: Int): StrikeRule =
        StrikeRule { candidates ->
            val atm = nearest(ATM).pick(candidates)
            atm?.let { candidates.getOrNull(candidates.indexOf(it) + steps) }
        }

    private fun top(rank: Int): StrikeRule =
        StrikeRule { candidates -> candidates.sortedByDescending { it.mid }.getOrNull(rank - 1) }
}
