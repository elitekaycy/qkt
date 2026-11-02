package com.qkt.app

import com.qkt.broker.BookedDeals
import com.qkt.broker.BookedDealsVenue
import com.qkt.broker.BookedFill
import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.VenueLeg
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.persistence.BookedDealPersistence
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What a session keeps of its venues' booked deals, and what it hands back after a restart. */
class BookedDealStateTest {
    private val now = 100L * DAY_MS
    private val clock = FixedClock(now)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    private open class Plain : Broker {
        override val name = "plain"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = error("not used")

        override fun cancel(orderId: String) = error("not used")

        override fun modify(
            orderId: String,
            changes: OrderModification,
        ) = error("not used")
    }

    private class Venue(
        var held: BookedDeals = NONE,
    ) : Plain(),
        BookedDealsVenue {
        val restored = mutableListOf<BookedDeals>()

        override fun bookedDeals() = held

        override fun restoreBookedDeals(saved: BookedDeals) {
            restored += saved
        }
    }

    private class Store(
        var stored: BookedDeals? = null,
        val unreadable: Boolean = false,
    ) : BookedDealPersistence {
        val saves = mutableListOf<BookedDeals>()

        override fun saveBookedDeals(
            ownerId: String,
            deals: BookedDeals,
        ) {
            saves += deals
        }

        override fun loadBookedDeals(ownerId: String): BookedDeals? {
            check(!unreadable) { "corrupt" }
            return stored
        }
    }

    private fun fill(
        id: String,
        strategy: String = "s",
        atMs: Long = now,
    ) = BookedFill("o-$id", id, VenueLeg("IN", "p", null), BigDecimal.ZERO, strategy, atMs)

    private fun accounted(
        strategy: String = "s",
        kind: FillAccountingKind = FillAccountingKind.EXECUTION,
    ) = FillAccountedEvent(
        orderId = "o",
        strategyId = strategy,
        symbol = "X",
        fillSliceId = "o:1",
        sourceFillSequenceId = 1,
        cumulativeFilled = null,
        modeledCommissionAccount = BigDecimal.ZERO,
        venueCostsAccount = BigDecimal.ZERO,
        totalCostsAccount = BigDecimal.ZERO,
        accountNativeRealized = BigDecimal.ZERO,
        strategyNativeRealized = BigDecimal.ZERO,
        nativeCurrency = "USD",
        grossAccountRealized = BigDecimal.ZERO,
        grossStrategyAccountRealized = BigDecimal.ZERO,
        accountCurrency = "USD",
        netAccountRealized = BigDecimal.ZERO,
        netStrategyAccountRealized = BigDecimal.ZERO,
        conversionRate = null,
        conversionTimestampMs = null,
        conversionSource = null,
        contractSize = BigDecimal.ONE,
        accountPositionBefore = null,
        accountPositionAfter = null,
        strategyPositionBefore = null,
        strategyPositionAfter = null,
        reducedExposure = false,
        partial = false,
        executedAt = now,
        kind = kind,
    )

    @Test
    fun `an execution saves this session's strategies' facts, not another's on the shared account`() {
        val venue = Venue(BookedDeals(listOf(fill("a"), fill("b", strategy = "other")), emptyList()))
        val store = Store()
        BookedDealState(bus, venue, store, clock).bind(listOf("s"))

        bus.publish(accounted())
        bus.publish(accounted(strategy = "other"))

        assertThat(store.saves.map { saved -> saved.fills.map { it.fillId } }).containsExactly(listOf("a"))
    }

    @Test
    fun `facts past the deal poll's reach are neither saved nor handed back`() {
        val old = fill("old", atMs = now - 31 * DAY_MS)
        val venue = Venue(BookedDeals(listOf(old, fill("new")), emptyList()))
        val store = Store(BookedDeals(listOf(old, fill("kept")), emptyList()))
        BookedDealState(bus, venue, store, clock).bind(listOf("s"))

        bus.publish(accounted())

        val handedBack = venue.restored.single()
        val saved = store.saves.single()
        assertThat(handedBack.fills.map { it.fillId }).containsExactly("kept")
        assertThat(saved.fills.map { it.fillId }).containsExactly("new")
    }

    @Test
    fun `an unreadable file does not stop the session, and nothing is handed back`() {
        val venue = Venue()

        BookedDealState(bus, venue, Store(unreadable = true), clock).bind(listOf("s"))

        assertThat(venue.restored).isEmpty()
    }

    @Test
    fun `a router reaches the venue behind it, and a venue with no such need saves nothing`() {
        val venue = Venue(BookedDeals(listOf(fill("a")), emptyList()))
        val routed =
            CompositeBroker(
                listOf(SymbolPattern.exactSet(setOf("A:X")) to venue, SymbolPattern.exactSet(setOf("B:X")) to Plain()),
            )
        val store = Store(BookedDeals(listOf(fill("r")), emptyList()))
        BookedDealState(bus, routed, store, clock).bind(listOf("s"))
        bus.publish(accounted())
        assertThat(venue.restored).hasSize(1)
        assertThat(store.saves).hasSize(1)

        val plainStore = Store()
        BookedDealState(EventBus(clock, MonotonicSequenceGenerator()), Plain(), plainStore, clock).bind(listOf("s"))
        assertThat(plainStore.saves).isEmpty()
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        val NONE = BookedDeals(emptyList(), emptyList())
    }
}
