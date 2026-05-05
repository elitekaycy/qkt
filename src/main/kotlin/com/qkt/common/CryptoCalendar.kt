package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

object CryptoCalendar : TradingCalendar {
    override val name: String = "crypto"

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean = true

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val day = t.atZone(ZoneOffset.UTC).toLocalDate()
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val end = start.plus(Duration.ofDays(1))
        return TimeRange(start, end)
    }

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long =
        when (anchor) {
            SessionAnchor.PreviousDay -> dayEpoch(t) - 1
            SessionAnchor.CurrentSession -> dayEpoch(t)
            SessionAnchor.PreviousSession -> dayEpoch(t) - 1
            is SessionAnchor.Rolling -> t.toEpochMilli()
        }

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange =
        when (anchor) {
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            SessionAnchor.CurrentSession,
            -> {
                val start = Instant.ofEpochSecond(anchorEpoch * 86_400L)
                TimeRange(start, start.plus(Duration.ofDays(1)))
            }
            is SessionAnchor.Rolling -> {
                val end = Instant.ofEpochMilli(anchorEpoch)
                TimeRange(end.minus(anchor.duration), end)
            }
        }

    private fun dayEpoch(t: Instant): Long = t.epochSecond / 86_400L
}
