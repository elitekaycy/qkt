package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The branches that settle without a venue answer; the `/positions` cross-check is covered end to end. */
class MT5PendingDisappearanceTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1", pollIntervalMs = 1_000L)
    private val clock = FixedClock(50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
    private val partialEntries = MT5PartialEntries(books, bus, clock)
    private val disappearance =
        MT5PendingDisappearance(
            profile,
            MT5Client(profile.gatewayUrl, profile.serverTimeZone, httpTimeoutMs = 500L, retryAttempts = 0),
            bus,
            clock,
            books,
            partialEntries,
            MT5PendingFills(profile, bus, clock, MT5Symbol(profile.symbolPolicy), books, partialEntries),
        )
    private val meta = MT5TicketMeta("dsl-gold--1", "gold_trend")

    init {
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
    }

    @Test
    fun `a ticket this session never placed is settled without an event`() {
        assertThat(disappearance.onPendingDisappeared(42L)).isTrue()
        assertThat(cancels).isEmpty()
    }

    @Test
    fun `a ticket that filled within three poll intervals is not reported as cancelled`() {
        books.pendingBook.attribute(42L, meta)
        books.recentlyFilledTickets[42L] = 47_001L

        assertThat(disappearance.onPendingDisappeared(42L)).isTrue()

        assertThat(cancels).isEmpty()
        assertThat(books.pendingBook.meta(42L)).isNull()
        assertThat(books.recentlyFilledTickets).doesNotContainKey(42L)
    }

    @Test
    fun `an unreadable venue leaves the order tracked for the next round instead of cancelling it`() {
        books.pendingBook.register(42L, meta)

        assertThat(disappearance.onPendingDisappeared(42L)).isFalse()

        assertThat(cancels).isEmpty()
        assertThat(books.pendingBook.ticketOf("dsl-gold--1")).isEqualTo(42L)
    }
}
