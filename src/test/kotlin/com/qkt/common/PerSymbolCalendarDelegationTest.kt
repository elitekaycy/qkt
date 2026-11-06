package com.qkt.common

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerSymbolCalendarDelegationTest {
    private val crypto = TradingCalendar.crypto()
    private val nyse = TradingCalendar.nyse()

    private val cal = PerSymbolCalendar(crypto, listOf("BTCUSD", "SPY")) { if (it == "SPY") nyse else crypto }

    @Test
    fun `closed sessions are answered by the symbol's own calendar`() {
        val julyFourth = TimeRange(Instant.parse("2024-07-04T13:30:00Z"), Instant.parse("2024-07-04T20:00:00Z"))

        assertThat(cal.isClosedSession("SPY", julyFourth)).isEqualTo(nyse.isClosedSession("SPY", julyFourth))
        assertThat(cal.isClosedSession("BTCUSD", julyFourth)).isEqualTo(crypto.isClosedSession("BTCUSD", julyFourth))
    }

    @Test
    fun `anchor ranges and short sessions follow the primary calendar`() {
        val t = Instant.parse("2024-06-12T12:00:00Z")
        val epoch = cal.anchorEpochFor(SessionAnchor.PreviousDay, t)

        assertThat(cal.rangeFor(SessionAnchor.PreviousDay, epoch)).isEqualTo(crypto.rangeFor(SessionAnchor.PreviousDay, epoch))
        assertThat(cal.hasShortSessions).isEqualTo(crypto.hasShortSessions)
    }

    @Test
    fun `the description names the primary and each resolved calendar`() {
        assertThat(cal.toString())
            .startsWith("PerSymbolCalendar(primary=${crypto.name}, resolved=")
            .contains("BTCUSD=${crypto.name}", "SPY=${nyse.name}")
    }
}
