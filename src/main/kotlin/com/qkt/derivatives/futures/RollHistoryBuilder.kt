package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Measures a root's rolls from stored bars: at each roll instant, the last close at or before it for
 * the contract being left and the contract being entered. Rolls before the first one it can price
 * are skipped (older contracts may have no data) and it stops at the next roll it cannot price, so a
 * history is always one contiguous run of rolls.
 */
class RollHistoryBuilder(
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
            val measured = schedule.transitions.map { t -> measure(schedule, t, selector.offset) }
            records += measured.dropWhile { it == null }.takeWhile { it != null }.filterNotNull()
        }
        return RollHistory(root.root, policy.key, records.distinct().sortedWith(compareBy({ it.atMs }, { it.from })))
    }

    private fun measure(
        schedule: RollSchedule,
        t: RollTransition,
        offset: Int,
    ): RollRecord? {
        val from = schedule.contracts.getOrNull(t.fromIndex + offset) ?: return null
        val to = schedule.contracts.getOrNull(t.toIndex + offset) ?: return null
        val fromPrice = priceAt(from.symbol, t.atMs) ?: return null
        val toPrice = priceAt(to.symbol, t.atMs) ?: return null
        return RollRecord(t.atMs, from.symbol, to.symbol, fromPrice.toPlainString(), toPrice.toPlainString())
    }

    private fun priceAt(
        contract: String,
        atMs: Long,
    ): BigDecimal? {
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate()
        return (bars(contract, day) + bars(contract, day.minusDays(1)))
            .filter { it.endTime <= atMs }
            .maxByOrNull { it.startTime }
            ?.close
    }
}
