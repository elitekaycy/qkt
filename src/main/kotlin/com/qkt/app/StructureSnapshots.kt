package com.qkt.app

import com.qkt.persistence.PersistedStructure
import com.qkt.persistence.PersistedStructureLeg
import com.qkt.persistence.StatePersistor

/** [this] structure as a restart persists it. */
internal fun LiveStructure.persisted(): PersistedStructure =
    PersistedStructure(
        id,
        alias,
        size,
        state,
        exit,
        legs.map { leg ->
            PersistedStructureLeg(
                leg.symbol,
                leg.side,
                leg.openOrderId,
                leg.contractSize,
                leg.expiryMs,
                leg.opened,
                leg.entryPrice,
                leg.held,
                leg.realized,
                leg.openEnded,
                leg.closingOrders.toMap(),
            )
        },
    )

/** The live structure [this] persisted state restores. */
internal fun PersistedStructure.live(): LiveStructure {
    val legs =
        legs.map { saved ->
            StructureLeg(saved.symbol, saved.side, saved.openOrderId, saved.contractSize, saved.expiryMs).apply {
                restore(saved.opened, saved.entryPrice, saved.held, saved.realized, saved.openEnded, saved.closing)
            }
        }
    return LiveStructure(id, alias, size, legs).also {
        it.state = state
        it.exit = exit
    }
}

/**
 * Keeps [strategyId]'s [book] across restarts through [persistor]: [restore] takes its structures back
 * before the strategy binds, and [save] writes them after each change. A strategy that never holds a
 * structure never writes; the save that empties the book is written, so a restart finds none.
 */
internal class StructurePersistence(
    private val strategyId: String,
    private val book: StructureBook,
    private val persistor: StatePersistor,
) {
    private var holding = false

    /** Restores the persisted structures into the book. */
    fun restore() {
        val restored = persistor.loadStructures(strategyId).map { it.live() }
        book.restore(restored)
        holding = restored.isNotEmpty()
    }

    /** Writes the book's structures when it holds any, or held some at the last save. */
    fun save() {
        val live = book.structures
        if (live.isEmpty() && !holding) return
        persistor.saveStructures(strategyId, live.map { it.persisted() })
        holding = live.isNotEmpty()
    }
}
