package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.position
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Position 9001 (0.10 XAUUSD long) shrinking or vanishing while a hook says the change is not settled yet. */
class MT5PositionPollerHooksTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = 1_800_000_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private var growth: BigDecimal? = null
    private var engineClose = EngineCloseState.NONE
    private var enginePartial: EnginePartialClose? = null
    private var rounds = 0

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        gateway.on("/history_deals_get", "[]")
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun poller(onPollRound: (() -> Unit)? = null) =
        MT5PositionPoller(
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            profile,
            MT5Symbol(profile.symbolPolicy),
            bus,
            clock,
            engineCloseState = { engineClose },
            takeEnginePartial = { enginePartial },
            entryGrowthBeside = { _, _ -> growth },
            onPollRound = onPollRound,
        )

    private fun venueHolds(vararg volumes: String?) {
        val snapshots = volumes.map { v -> if (v == null) "[]" else "[" + position(9001, v, priceOpen = "2400") + "]" }
        gateway.on("/get_positions", *snapshots.toTypedArray())
    }

    @Test
    fun `a poll round hook that throws does not stop the round`() {
        venueHolds("0.10")

        poller {
            rounds++
            error("hook failed")
        }.tick()

        assertThat(rounds).isEqualTo(1)
        assertThat(gateway.calls("/get_positions")).hasSize(1)
    }

    @Test
    fun `a shrink is held while entry history is unsettled, then booked from the last settled volume`() {
        venueHolds("0.10", "0.06")
        val poller = poller()
        poller.tick()

        growth = null
        poller.tick()
        assertThat(fills).isEmpty()

        growth = BigDecimal.ZERO
        poller.tick()
        assertThat(fills.single().quantity).isEqualByComparingTo("0.04")
    }

    @Test
    fun `a vanished ticket is held while entry history is unsettled, then booked whole`() {
        venueHolds("0.10", null)
        val poller = poller()
        growth = BigDecimal.ZERO
        poller.tick()

        growth = null
        poller.tick()
        assertThat(fills).isEmpty()

        growth = BigDecimal.ZERO
        poller.tick()
        assertThat(fills.single().quantity).isEqualByComparingTo("0.10")
        assertThat(fills.single().clientOrderId).isEqualTo("mt5-close-9001")
    }

    @Test
    fun `a shrink while the engine's own close is pending waits for that close`() {
        venueHolds("0.10", "0.06")
        growth = BigDecimal.ZERO
        val poller = poller()
        poller.tick()

        engineClose = EngineCloseState.PENDING
        poller.tick()
        assertThat(fills).isEmpty()

        engineClose = EngineCloseState.NONE
        poller.tick()
        assertThat(fills.single().quantity).isEqualByComparingTo("0.04")
    }

    @Test
    fun `a shrink the engine's confirmed close fully explains books nothing more`() {
        venueHolds("0.10", "0.06")
        growth = BigDecimal.ZERO
        val poller = poller()
        poller.tick()

        engineClose = EngineCloseState.CONFIRMED
        enginePartial = null
        poller.tick()

        assertThat(fills).isEmpty()
    }

    @Test
    fun `a shrink beyond the engine's confirmed close books the venue's own part`() {
        venueHolds("0.10", "0.06")
        growth = BigDecimal.ZERO
        val poller = poller()
        poller.tick()

        engineClose = EngineCloseState.CONFIRMED
        enginePartial = EnginePartialClose(BigDecimal("0.01"), setOf(55L))
        poller.tick()

        assertThat(fills.single().quantity).isEqualByComparingTo("0.03")
    }
}
