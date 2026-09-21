package com.qkt.connector.mt5

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A `calendars:` block adds to the profile's calendars. It used to replace them: the attestation's
 * `BTC*: crypto` dropped the Exness profile's metals break, so a daemon started at 21:35 UTC read
 * gold's paused history as a broker clock fault and could not deploy any gold strategy until 22:00.
 */
class MT5ProfileCalendarLayeringTest {
    private val loader = MT5BrokerProfileLoader()
    private val insideGoldBreak = Instant.parse("2026-09-21T21:35:00Z")

    private fun profile(vararg rules: Pair<String, String>) =
        loader
            .load(
                mapOf(
                    "x" to
                        mapOf("type" to "mt5", "extends" to "exness", "gateway_url" to "http://h", "magic" to "11000"),
                ),
                MT5DefaultProfiles.all,
                env = emptyMap(),
                calendars = mapOf("x" to rules.toList()),
            ).first { it.name == "x" }

    @Test
    fun `adding a crypto calendar for BTC keeps the profile's gold break`() {
        val calendars = profile("BTC*" to "crypto").symbolCalendars

        assertThat(calendars.calendarFor("XAUUSD").isScheduledBreak("XAUUSD", insideGoldBreak)).isTrue()
        assertThat(calendars.calendarFor("BTCUSD").name).isEqualTo("crypto")
    }

    @Test
    fun `a catch-all sets the fallback without shadowing the profile's specific rules`() {
        val calendars = profile("BTC*" to "crypto", "*" to "fx").symbolCalendars

        assertThat(calendars.calendarFor("XAUUSD").isScheduledBreak("XAUUSD", insideGoldBreak)).isTrue()
        assertThat(calendars.calendarFor("EURUSD").name).isEqualTo("fx")
    }

    @Test
    fun `a rule for the same pattern still replaces the profile's`() {
        val calendars = profile("XAU*" to "fx").symbolCalendars

        assertThat(calendars.calendarFor("XAUUSD").isScheduledBreak("XAUUSD", insideGoldBreak)).isFalse()
    }
}
