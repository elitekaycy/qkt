package com.qkt.app

import com.qkt.broker.BookedDeals
import com.qkt.broker.Broker
import com.qkt.broker.bookedDealsVenues
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.persistence.BookedDealPersistence
import org.slf4j.LoggerFactory

/**
 * Keeps what this session booked of its venues' deals across a restart ([com.qkt.broker.BookedDealsVenue]): each
 * execution saves the facts of this session's strategies under the first of them, and the next session hands them
 * back. A deal a retained fact states may be reported again under the same ticket, which a deal's reader takes once.
 * A session on no such venue subscribes to nothing. The facts only report deals, never trading, so a file that
 * cannot be read is logged and start-up goes on.
 */
internal class BookedDealState(
    private val bus: EventBus,
    private val broker: Broker,
    private val persistence: BookedDealPersistence,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(BookedDealState::class.java)

    /** Keep for [strategyIds], the strategies this pipeline runs; the first owns the persisted state. */
    fun bind(strategyIds: List<String>) {
        val owner = strategyIds.firstOrNull() ?: return
        val venues = broker.bookedDealsVenues()
        if (venues.isEmpty()) return
        val saved =
            runCatching { persistence.loadBookedDeals(owner) }
                .onFailure { log.warn("booked deals of {} cannot be read; earlier deals stay unreported", owner, it) }
                .getOrNull()
        saved?.let { all -> venues.forEach { it.restoreBookedDeals(recent(all)) } }
        val owned = strategyIds.toSet()
        bus.subscribe<FillAccountedEvent> { e ->
            if (e.kind != FillAccountingKind.EXECUTION || e.strategyId !in owned) return@subscribe
            val held = venues.map { it.bookedDeals() }
            val fills = held.flatMap { it.fills }.filter { it.strategyId in owned }
            val closes = held.flatMap { it.settledCloses }.filter { it.strategyId in owned }
            persistence.saveBookedDeals(owner, recent(BookedDeals(fills, closes)))
        }
    }

    private fun recent(deals: BookedDeals): BookedDeals {
        val oldest = clock.now() - RETAIN_MS
        return BookedDeals(deals.fills.filter { it.atMs >= oldest }, deals.settledCloses.filter { it.atMs >= oldest })
    }

    private companion object {
        /** As far back as a deal poll reads after a restart by default. */
        const val RETAIN_MS = 30 * 86_400_000L
    }
}
