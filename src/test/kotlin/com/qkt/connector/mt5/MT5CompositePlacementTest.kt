package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Leg-id decoding only; placement and rollback against a gateway live in MT5BrokerOcoPlacementRollbackTest. */
class MT5CompositePlacementTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1")
    private val clock = FixedClock(1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val client = MT5Client(profile.gatewayUrl, profile.serverTimeZone, retryAttempts = 0)
    private val symbol = MT5Symbol(profile.symbolPolicy)
    private val composite =
        MT5CompositePlacement(
            profile,
            client,
            bus,
            clock,
            MT5PlacementPreparation(profile, client, null, symbol, books.symbolMeta),
            SequentialIdGenerator(prefix = "t"),
            books,
            MT5BrokerEvents(profile, bus, clock),
            MT5PendingFills(profile, bus, clock, symbol, books, MT5PartialEntries(books, bus, clock)),
        )

    @Test
    fun `an oco leg comment yields the leg's own order id`() {
        assertThat(composite.decodeOcoLegOrderId("oco:range-break/range-break-buy")).isEqualTo("range-break-buy")
    }

    @Test
    fun `a leg id that itself contains a slash is kept whole`() {
        assertThat(composite.decodeOcoLegOrderId("oco:parent/leg/a")).isEqualTo("leg/a")
    }

    @Test
    fun `comments that are not oco legs, or carry no leg id, fall back to the parent`() {
        assertThat(composite.decodeOcoLegOrderId("range-break-buy")).isNull()
        assertThat(composite.decodeOcoLegOrderId("oco:range-break")).isNull()
        assertThat(composite.decodeOcoLegOrderId("oco:range-break/")).isNull()
    }
}
