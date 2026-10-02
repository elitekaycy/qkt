package com.qkt.events

import com.qkt.strategy.StructureLegPosition
import java.math.BigDecimal

/** How an option structure left its strategy's book. */
enum class StructureOutcome {
    /** Its legs were closed: by `CLOSE`, a flatten, or orders outside the structure. */
    CLOSED,

    /** A leg failed while it opened, and the legs that filled were closed. */
    UNWOUND,

    /** Its last held legs settled at expiry. */
    SETTLED,
}

/** The life of option structure [structureId], opened under [alias] by [strategyId], as its book records it. */
sealed interface StructureEvent : Event {
    val strategyId: String
    val structureId: String
    val alias: String

    /** The legs at the moment of the event, each with its entry and what is still held. */
    val legs: List<StructureLegPosition>
}

/** Every leg of [structureId] filled; [credit] is the premium received, negative for a debit. */
data class StructureOpened(
    override val strategyId: String,
    override val structureId: String,
    override val alias: String,
    override val legs: List<StructureLegPosition>,
    val credit: BigDecimal,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : StructureEvent

/**
 * [structureId] left the book, nothing of it held or working, by [outcome]; [realized] is its premium
 * P&L before fees.
 */
data class StructureClosed(
    override val strategyId: String,
    override val structureId: String,
    override val alias: String,
    override val legs: List<StructureLegPosition>,
    val outcome: StructureOutcome,
    val realized: BigDecimal,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : StructureEvent
