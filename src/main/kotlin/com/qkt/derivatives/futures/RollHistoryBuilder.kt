package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Measures a root's rolls from stored bars [barMs] long by [RollPricing]: at each roll instant, the last close at or
 * before it for the contract being left and the contract being entered. It keeps the latest contiguous run of rolls
 * it can price: a roll it cannot price (no data, or, as on Binance before late 2023, the next
 * contract not yet listed at the roll instant) ends everything before it, so a history is always one
 * contiguous run and never mixes eras separated by a gap. A roll that has not passed at [asOfMs]
 * ([RollPricing.hasPassed]) is not measured: it is in the future, and its record is written when it has happened, by
 * a later fetch or by the live session that reaches it ([LiveRollMeasurer]). A roll the bars cannot price keeps its
 * record in [known] (the history being rebuilt, of the same policy): a live session measured it from the venue's own
 * bars, and dropping it would end the history before a roll live has traded on.
 *
 * A rebuild over [known] keeps its records and only appends rolls after the last one (#1754): a record is the reference
 * a session booked its positions and `cost:roll` against, and re-pricing it would move the series every strategy reads
 * by a jump the market never made. Nor does it add rolls before the first: the series is adjusted forward from its
 * first roll, so a roll prepended would shift every contract after it. [remeasure] prices every roll the bars can again
 * (`qkt fetch --rolls --remeasure`); a running session then books the move as a series rebase.
 */
class RollHistoryBuilder(
    private val barMs: Long = 60_000L,
    private val asOfMs: Long = Long.MAX_VALUE,
    private val known: RollHistory? = null,
    private val remeasure: Boolean = false,
    private val bars: (contract: String, day: LocalDate) -> List<Candle>,
) {
    /** The history of [root] over [catalog] for [selectors]. */
    fun build(
        root: FuturesRoot,
        catalog: ContractCatalog,
        selectors: Set<ContinuousSelector> = ContinuousSelector.entries.toSet(),
    ): RollHistory {
        val policy = requireNotNull(root.roll) { "futures root ${root.root} has no roll policy" }
        val schedule = RollSchedule(catalog.contracts, policy)
        val records = mutableListOf<RollRecord>()
        for (selector in selectors.sortedBy { it.offset }) {
            val rolls = schedule.transitions.map { contracts(schedule, it, selector.offset) }
            val kept = rolls.map { roll -> roll?.takeUnless { remeasure }?.let(::knownRecord) }
            val last = kept.indexOfLast { it != null }
            records += latestRun(rolls.mapIndexed { i, roll -> if (i <= last) kept[i] else roll?.let(::measure) })
        }
        return RollHistory(root.root, policy.key, records.distinct().sortedWith(compareBy({ it.atMs }, { it.from })))
    }

    /** The last contiguous block of measured rolls; rolls after it are either in the future or unmeasurable. */
    private fun latestRun(measured: List<RollRecord?>): List<RollRecord> {
        val end = measured.indexOfLast { it != null }
        if (end < 0) return emptyList()
        val start = (end downTo 0).firstOrNull { measured[it] == null }?.plus(1) ?: 0
        return measured.subList(start, end + 1).filterNotNull()
    }

    /** One roll of one selector: its instant and the contracts it leaves and enters. */
    private data class Roll(
        val atMs: Long,
        val from: String,
        val to: String,
    )

    /** Roll [t] for the selector [offset] contracts on; null before it has passed or past the catalog. */
    private fun contracts(
        schedule: RollSchedule,
        t: RollTransition,
        offset: Int,
    ): Roll? {
        if (!RollPricing.hasPassed(t.atMs, asOfMs)) return null
        val from = schedule.contracts.getOrNull(t.fromIndex + offset) ?: return null
        val to = schedule.contracts.getOrNull(t.toIndex + offset) ?: return null
        return Roll(t.atMs, from.symbol, to.symbol)
    }

    /** [roll] priced from the bars, else [known]'s record of it: a live session measured it from the venue's bars. */
    private fun measure(roll: Roll): RollRecord? {
        val fromPrice = priceAt(roll.from, roll.atMs)
        val toPrice = priceAt(roll.to, roll.atMs)
        if (fromPrice == null || toPrice == null) return knownRecord(roll)
        return RollRecord(roll.atMs, roll.from, roll.to, fromPrice.toPlainString(), toPrice.toPlainString())
    }

    private fun knownRecord(roll: Roll): RollRecord? = known?.find(roll.atMs, roll.from, roll.to)

    private fun priceAt(
        contract: String,
        atMs: Long,
    ): BigDecimal? = RollPricing.closeAtOrBefore(RollPricing.days(atMs, barMs).flatMap { bars(contract, it) }, atMs)
}
