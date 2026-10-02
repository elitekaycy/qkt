package com.qkt.backtest

import com.qkt.events.StructureClosed
import com.qkt.events.StructureEvent
import com.qkt.events.StructureOpened
import com.qkt.events.StructureOutcome
import com.qkt.strategy.StructureLegPosition
import java.math.BigDecimal

/**
 * One option structure of a run: [legs] with their entries; [openedAt] and [credit] once every leg
 * filled (null for one unwound before it opened whole); [closedAt], [outcome] and [realized] (premium
 * P&L before fees) once it left its book (null for one still live when the run ended).
 */
data class StructureRow(
    val strategyId: String,
    val structureId: String,
    val alias: String,
    val legs: List<StructureLegPosition>,
    val openedAt: Long? = null,
    val credit: BigDecimal? = null,
    val closedAt: Long? = null,
    val outcome: StructureOutcome? = null,
    val realized: BigDecimal? = null,
)

/** The run's option structures, one row each in the order they first appeared, from their books' events. */
class StructureLog {
    private val rows = LinkedHashMap<Pair<String, String>, StructureRow>()

    /** Every structure seen, oldest first. */
    val entries: List<StructureRow> get() = rows.values.toList()

    /** Records [event] on its structure's row. */
    fun record(event: StructureEvent) {
        val key = event.strategyId to event.structureId
        val row = rows[key] ?: StructureRow(event.strategyId, event.structureId, event.alias, event.legs)
        rows[key] =
            when (event) {
                is StructureOpened -> row.copy(legs = event.legs, openedAt = event.timestamp, credit = event.credit)
                is StructureClosed ->
                    row.copy(
                        legs = event.legs,
                        closedAt = event.timestamp,
                        outcome = event.outcome,
                        realized = event.realized,
                    )
            }
    }
}
