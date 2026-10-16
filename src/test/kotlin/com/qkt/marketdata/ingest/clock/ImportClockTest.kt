package com.qkt.marketdata.ingest.clock

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.InputLine
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ImportClockTest {
    private val line = InputLine("f.csv", 1, "")

    private fun utc(
        clock: ImportClock,
        local: String,
    ): Instant = Instant.ofEpochMilli(LocalTimeResolver(clock).toEpochMs(LocalDateTime.parse(local), line))

    @Test
    fun `parses every clock name and refuses a bare GMT+2`() {
        assertThat(ImportClock.parse("UTC")).isEqualTo(ImportClock.Utc)
        assertThat(ImportClock.parse("ny-close")).isEqualTo(ImportClock.NyClose)
        assertThat(ImportClock.parse("+02:00")).isEqualTo(ImportClock.Fixed(ZoneOffset.ofHours(2)))
        assertThat(ImportClock.parse("-5")).isEqualTo(ImportClock.Fixed(ZoneOffset.ofHours(-5)))
        assertThat(ImportClock.parse("Europe/Athens").spec).isEqualTo("Europe/Athens")
        assertThatThrownBy { ImportClock.parse("GMT+2") }.hasMessageContaining("ambiguous")
        assertThatThrownBy { ImportClock.parse("Mars/Olympus") }.hasMessageContaining("unknown clock")
    }

    @Test
    fun `ny-close midnight is the five pm New York rollover in winter and summer`() {
        assertThat(utc(ImportClock.NyClose, "2024-01-15T00:00")).isEqualTo(Instant.parse("2024-01-14T22:00:00Z"))
        assertThat(utc(ImportClock.NyClose, "2024-07-15T00:00")).isEqualTo(Instant.parse("2024-07-14T21:00:00Z"))
    }

    @Test
    fun `ny-close follows US DST dates where Europe-Athens does not`() {
        val usSwitchedEuNot = "2024-03-11T00:00"

        assertThat(utc(ImportClock.NyClose, usSwitchedEuNot)).isEqualTo(Instant.parse("2024-03-10T21:00:00Z"))
        assertThat(
            utc(ImportClock.parse("Europe/Athens"), usSwitchedEuNot),
        ).isEqualTo(Instant.parse("2024-03-10T22:00:00Z"))
    }

    @Test
    fun `a wall-clock time inside a DST gap names no instant and refuses`() {
        assertThatThrownBy { utc(ImportClock.parse("America/New_York"), "2024-03-10T02:30") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("DST gap")
    }

    @Test
    fun `the repeated DST hour is resolved by the stream stepping back`() {
        val r = LocalTimeResolver(ImportClock.parse("America/New_York"))
        val times =
            listOf("2024-11-03T00:30", "2024-11-03T01:30", "2024-11-03T01:10", "2024-11-03T01:50", "2024-11-03T02:10")
        val utc = times.map { Instant.ofEpochMilli(r.toEpochMs(LocalDateTime.parse(it), line)).toString() }

        assertThat(utc).containsExactly(
            "2024-11-03T04:30:00Z",
            "2024-11-03T05:30:00Z",
            "2024-11-03T06:10:00Z",
            "2024-11-03T06:50:00Z",
            "2024-11-03T07:10:00Z",
        )
        assertThat(r.sawOverlap).isTrue()
    }

    @Test
    fun `a stream that steps back twice in the repeated hour refuses`() {
        val r = LocalTimeResolver(ImportClock.parse("America/New_York"))
        listOf("2024-11-03T00:50", "2024-11-03T01:40", "2024-11-03T01:10").forEach {
            r.toEpochMs(LocalDateTime.parse(it), line)
        }

        assertThatThrownBy { r.toEpochMs(LocalDateTime.parse("2024-11-03T01:05"), line) }.hasMessageContaining("twice")
    }

    @Test
    fun `a file that starts inside the repeated hour refuses`() {
        assertThatThrownBy {
            utc(
                ImportClock.parse("America/New_York"),
                "2024-11-03T01:30",
            )
        }.hasMessageContaining("starts at")
    }
}
