package com.qkt.connector.mt5.marketdata

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A tick feed and its stale-probe cadence refuse every interval that is not positive. */
class Mt5TickFeedSourceSettingsTest {
    private fun feed(
        poll: Long = 1L,
        outOfSession: Long = 1L,
        prolongedStale: Long = 1L,
        probe: Long = 1L,
        lookback: Long = 1L,
        catchup: Long = 1L,
    ) = Mt5TickFeedSource(
        baseUrl = "http://127.0.0.1:1",
        symbolMap = mapOf("XAUUSDm" to "EXNESS:XAUUSD"),
        pollIntervalMs = poll,
        outOfSessionSleepMs = outOfSession,
        prolongedStaleAfterMs = prolongedStale,
        staleProbeSleepMs = probe,
        initialLookbackMs = lookback,
        maxCatchupMs = catchup,
    )

    @Test
    fun `every feed interval must be positive`() {
        assertThatCode { feed() }.doesNotThrowAnyException()
        val refusals =
            mapOf<String, () -> Any>(
                "MT5 tick poll interval must be positive" to { feed(poll = 0L) },
                "MT5 out-of-session sleep must be positive" to { feed(outOfSession = 0L) },
                "MT5 prolonged-stale threshold must be positive" to { feed(prolongedStale = -1L) },
                "MT5 stale probe sleep must be positive" to { feed(probe = 0L) },
                "MT5 initial lookback must be positive" to { feed(lookback = 0L) },
                "MT5 max catch-up window must be positive" to { feed(catchup = 0L) },
            )
        refusals.forEach { (message, build) ->
            assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(message)
        }
    }

    @Test
    fun `a stopped feed that never started stays stopped`() {
        val source = feed()

        assertThatCode { source.stop() }.doesNotThrowAnyException()
        assertThatCode { source.stop() }.doesNotThrowAnyException()
    }

    @Test
    fun `every stale-probe interval must be positive`() {
        assertThatThrownBy { ProlongedStaleProbeController(0L, 1L, 1L) }
            .hasMessage("normal poll interval must be positive")
        assertThatThrownBy { ProlongedStaleProbeController(1L, 0L, 1L) }
            .hasMessage("stale-after interval must be positive")
        assertThatThrownBy { ProlongedStaleProbeController(1L, 1L, 0L) }
            .hasMessage("probe poll interval must be positive")
    }
}
