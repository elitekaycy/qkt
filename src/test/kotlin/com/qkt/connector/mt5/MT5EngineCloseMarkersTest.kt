package com.qkt.connector.mt5

import com.qkt.common.FixedClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5EngineCloseMarkersTest {
    /** TTL = max(3 x 1000, 5000 + 1000) = 6000 ms. */
    private val profile =
        MT5DefaultProfiles.exness.copy(
            gatewayUrl = "http://127.0.0.1:1",
            pollIntervalMs = 1_000L,
            httpTimeoutMs = 5_000L,
        )
    private val clock = FixedClock(100_000L)
    private val markers = MT5EngineCloseMarkers(profile, clock)

    @Test
    fun `a ticket the engine never closed has no marker`() {
        assertThat(markers.engineCloseState(7L)).isEqualTo(EngineCloseState.NONE)
    }

    @Test
    fun `an unanswered close stays pending however long the venue takes`() {
        markers.begin(7L, clock.now())
        clock.time += 60_000L

        assertThat(markers.engineCloseState(7L)).isEqualTo(EngineCloseState.PENDING)
    }

    @Test
    fun `a confirmed close suppresses the poller until its ttl passes`() {
        markers.begin(7L, clock.now())
        markers.confirmEngineClose(7L)

        clock.time += 5_999L
        assertThat(markers.engineCloseState(7L)).isEqualTo(EngineCloseState.CONFIRMED)
        clock.time += 1L
        assertThat(markers.engineCloseState(7L)).isEqualTo(EngineCloseState.NONE)
    }

    @Test
    fun `confirming a close that was already withdrawn does not bring the marker back`() {
        markers.begin(7L, clock.now())
        markers.remove(7L)
        markers.confirmEngineClose(7L)

        assertThat(markers.engineCloseState(7L)).isEqualTo(EngineCloseState.NONE)
    }
}
