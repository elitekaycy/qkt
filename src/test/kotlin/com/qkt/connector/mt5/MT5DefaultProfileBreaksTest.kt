package com.qkt.connector.mt5

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The built-in break windows match the quote gaps measured on each venue, so a closed market reads
 * PAUSED rather than STALE. Times are UTC; September is New York summer time (close = 21:00 UTC).
 */
class MT5DefaultProfileBreaksTest {
    private fun at(iso: String) = Instant.parse(iso)

    private fun MT5BrokerProfile.breakAt(
        symbol: String,
        iso: String,
    ) = symbolCalendars.calendarFor(symbol).isScheduledBreak(symbol, at(iso))

    @Test
    fun `exness metals break covers the measured 20-58 to 22-06 gap once a stale threshold has passed`() {
        val exness = MT5DefaultProfiles.exness
        assertThat(exness.breakAt("XAUUSD", "2026-09-22T20:54:00Z")).isFalse()
        // Last print 20:57:58 plus a 60 s threshold: noticed at 20:58:58.
        assertThat(exness.breakAt("XAUUSD", "2026-09-22T20:58:58Z")).isTrue()
        assertThat(exness.breakAt("XAGUSD", "2026-09-22T22:06:00Z")).isTrue()
        assertThat(exness.breakAt("XAUUSD", "2026-09-22T22:11:00Z")).isFalse()
    }

    @Test
    fun `the break follows New York across daylight saving`() {
        val exness = MT5DefaultProfiles.exness
        assertThat(exness.breakAt("XAUUSD", "2026-12-01T21:30:00Z")).isFalse()
        assertThat(exness.breakAt("XAUUSD", "2026-12-01T22:30:00Z")).isTrue()
    }

    @Test
    fun `exness fx rollover is a short pause`() {
        val exness = MT5DefaultProfiles.exness
        assertThat(exness.breakAt("AUDUSD", "2026-09-22T21:04:00Z")).isTrue()
        assertThat(exness.breakAt("GBPUSD", "2026-09-22T21:10:59Z")).isTrue()
        assertThat(exness.breakAt("EURUSD", "2026-09-22T21:16:00Z")).isFalse()
    }

    @Test
    fun `exness copper closes on London time`() {
        val exness = MT5DefaultProfiles.exness
        assertThat(exness.breakAt("XCUUSD", "2026-09-22T17:40:00Z")).isFalse()
        assertThat(exness.breakAt("XCUUSD", "2026-09-22T17:57:00Z")).isTrue()
        assertThat(exness.breakAt("XCUUSD", "2026-09-23T00:05:00Z")).isTrue()
        assertThat(exness.breakAt("XCUUSD", "2026-09-23T00:15:00Z")).isFalse()
    }

    @Test
    fun `exness crypto trades through the weekend so its feed keeps polling`() {
        val calendars = MT5DefaultProfiles.exness.symbolCalendars
        val saturday = at("2026-09-19T12:00:00Z")

        assertThat(calendars.calendarFor("BTCUSD").name).isEqualTo("crypto")
        assertThat(calendars.calendarFor("BTCUSD").isInSession("BTCUSD", saturday)).isTrue()
        assertThat(calendars.anyCalendarInSession(saturday)).isTrue()
        assertThat(calendars.calendarFor("EURUSD").isInSession("EURUSD", saturday)).isFalse()
    }

    @Test
    fun `ic markets keeps gold paused until it reopens at 22-02`() {
        val ic = MT5DefaultProfiles.icmarkets
        assertThat(ic.breakAt("XAUUSD", "2026-09-23T22:01:30Z")).isTrue()
        assertThat(ic.breakAt("XAGUSD", "2026-09-23T20:59:52Z")).isTrue()
        assertThat(ic.breakAt("EURUSD", "2026-09-23T20:59:30Z")).isTrue()
    }

    @Test
    fun `the5ers pauses earlier and longer, and BTC stays on its weekday calendar`() {
        val the5ers = MT5DefaultProfiles.the5ers
        // Metals last print 20:50:00 plus 60 s.
        assertThat(the5ers.breakAt("XAUUSD", "2026-09-17T20:51:00Z")).isTrue()
        assertThat(the5ers.breakAt("XAGUSD", "2026-09-17T22:05:58Z")).isTrue()
        assertThat(the5ers.breakAt("EURUSD", "2026-09-17T20:55:41Z")).isTrue()
        assertThat(the5ers.breakAt("BTCUSD", "2026-09-17T21:05:59Z")).isTrue()
        assertThat(the5ers.breakAt("NZDUSD", "2026-09-17T21:12:00Z")).isFalse()
        assertThat(the5ers.symbolCalendars.calendarFor("BTCUSD").name).isNotEqualTo("crypto")
        assertThat(the5ers.serverTimeZone).isEqualTo(MT5ServerTimeZone.NEW_YORK_CLOSE)
    }

    @Test
    fun `a profile with no built-in calendars defaults to all-fx`() {
        val raw = mapOf("ftmo" to mapOf("type" to "mt5", "gateway_url" to "http://h"))
        val p = MT5BrokerProfileLoader().load(raw, MT5DefaultProfiles.all, env = emptyMap()).first { it.name == "ftmo" }
        assertThat(p.symbolCalendars.calendarFor("EURUSD").name).isEqualTo("fx")
        assertThat(p.symbolCalendars.calendarFor("BTCUSD").name).isEqualTo("fx")
    }
}
