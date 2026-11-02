package com.qkt.common

import com.qkt.candles.TimeWindow
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Every built-in calendar answers every question, so a half-built calendar cannot ship (#calendar-resolution). */
class TradingCalendarCompletenessTest {
    // Every named calendar, plus a windowed NYSE calendar as Exness quotes stock CFDs.
    private val calendars =
        (TradingCalendar.names + "nyse[06:00-15:45]").map { requireNotNull(TradingCalendar.named(it)) { it } }
    private val windows = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "7d").map(TimeWindow::parse)

    @Test
    fun `every built-in calendar counts positive trading periods for every common window`() {
        for (calendar in calendars) {
            for (window in windows) {
                assertThat(calendar.tradingPeriodsPerYear(window))
                    .describedAs("${calendar.name} ${window.canonicalSpec()}")
                    .isPositive()
            }
        }
    }

    @Test
    fun `every built-in calendar answers the session and anchor questions`() {
        val t = Instant.parse("2024-03-13T15:00:00Z")
        for (calendar in calendars) {
            calendar.isInSession("X", t)
            calendar.isScheduledBreak("X", t)
            val session = calendar.sessionRange("X", t)
            calendar.isClosedSession("X", session)
            assertThat(session.to).describedAs(calendar.name).isAfter(session.from)
            for (anchor in listOf(
                SessionAnchor.CurrentSession,
                SessionAnchor.PreviousDay,
                SessionAnchor.PreviousSession,
            )) {
                val range = calendar.rangeFor(anchor, calendar.anchorEpochFor(anchor, t))
                assertThat(range.to).describedAs("${calendar.name} $anchor").isAfter(range.from)
            }
        }
    }

    @Test
    fun `every built-in calendar opens and closes only on five minute marks`() {
        val from =
            TradingPeriods.REFERENCE_FROM
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val to =
            TradingPeriods.REFERENCE_TO
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        for (calendar in calendars) {
            var t = from
            var blockState = false
            while (t < to) {
                val state = calendar.isInSession("X", Instant.ofEpochMilli(t))
                if ((t - from) % 300_000L == 0L) {
                    blockState = state
                } else if (state != blockState) {
                    throw AssertionError(
                        "${calendar.name} changes session inside a 5-minute block at ${Instant.ofEpochMilli(t)}",
                    )
                }
                t += 60_000L
            }
        }
    }
}
