package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.position
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A resting order that leaves the venue's list after part of it filled: the filled part stays, the rest is settled. */
class MT5PendingDisappearancePartialTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
    private val partialEntries = MT5PartialEntries(books, bus, clock)
    private val disappearance =
        MT5PendingDisappearance(
            profile,
            MT5Client(gateway.url, profile.serverTimeZone, httpTimeoutMs = 2_000L, retryAttempts = 0),
            bus,
            clock,
            books,
            partialEntries,
            MT5PendingFills(profile, bus, clock, MT5Symbol(profile.symbolPolicy), books, partialEntries),
        )
    private val meta = MT5TicketMeta("ord-1", "gold", requestedQuantity = BigDecimal("0.10"))

    init {
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `a vanished order whose position shows only part of it books the part and drops the rest`() {
        books.pendingBook.register(42L, meta)
        gateway.on("/get_positions", "[" + position(42, "0.04") + "]")

        assertThat(disappearance.onPendingDisappeared(42L)).isTrue()

        assertThat(cancels.single().reason).contains("residual disappeared")
        assertThat(books.partialPositionByResidualTicket).doesNotContainKey(42L)
    }

    @Test
    fun `a vanished part-filled order whose history cannot be read yet is asked about again`() {
        books.pendingBook.register(42L, meta)
        partialEntries.registerPartialEntry(
            PartialEntryState(
                meta = meta,
                residualTicket = 42L,
                positionTicket = 7000L,
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                requestedQuantity = BigDecimal("0.10"),
                cumulativeFilled = BigDecimal("0.04"),
                averageFillPrice = BigDecimal("2400"),
            ),
            openedAtMs = 1L,
        )
        gateway.on("/get_positions", "[]")

        assertThat(disappearance.onPendingDisappeared(42L)).isFalse()

        assertThat(cancels).isEmpty()
        assertThat(books.partialPositionByResidualTicket).containsKey(42L)
    }
}
