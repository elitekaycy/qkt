package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The built-in profiles by name, and a look-alike position claimed once. */
class MT5ProfilesAndFillClaimTest {
    @Test
    fun `every built-in profile carries its own name and magic`() {
        assertThat(MT5DefaultProfiles.ftmo.name).isEqualTo("ftmo")
        assertThat(MT5DefaultProfiles.ftmo.magic).isEqualTo(10003)
        assertThat(MT5DefaultProfiles.pepperstone.name).isEqualTo("pepperstone")
    }

    @Test
    fun `a position already owned is not claimed for a second order`() {
        val clock = FixedClock(1L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val events = mutableListOf<BrokerEvent>()
        bus.subscribe<BrokerEvent.OrderFilled> { events.add(it) }
        val books = MT5BrokerState(MT5DefaultProfiles.exness)
        val claim = MT5UnknownFillClaim(books, bus, clock)
        val position =
            MT5Position(
                77L,
                "XAUUSDm",
                0,
                BigDecimal("0.10"),
                BigDecimal("2400"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                10001,
                1L,
            )

        fun order(id: String) =
            OrderRequest.Market(id, "EXNESS:XAUUSD", Side.BUY, BigDecimal("0.10"), TimeInForce.GTC, 1L, "gold")

        assertThat(claim.claimFilled(order("a"), position, null)).isTrue()
        assertThat(claim.claimFilled(order("b"), position, null)).isFalse()
        assertThat(events).hasSize(1)
    }
}
