package com.qkt.marketdata.ingest.clock

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.InputLine
import java.time.Instant
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The clock language `--tz` and a preset's `clock:` speak: a zone shifted by hours, and eras of clocks. */
class ImportClockGrammarTest {
    private val line = InputLine("f.csv", 1, "")

    private fun utc(
        clock: String,
        wall: String,
    ): String =
        Instant
            .ofEpochMilli(LocalTimeResolver(ImportClock.parse(clock)).toEpochMs(LocalDateTime.parse(wall), line))
            .toString()

    @Test
    fun `a shifted zone follows that zone's DST dates at the shifted offsets`() {
        assertThat(utc("Europe/London-5h", "2025-01-13T06:00")).isEqualTo("2025-01-13T11:00:00Z")
        assertThat(utc("Europe/London-5h", "2025-07-14T06:00")).isEqualTo("2025-07-14T10:00:00Z")
        // London has not switched yet on 2025-03-10, New York has: the London rule decides.
        assertThat(utc("Europe/London-5h", "2025-03-10T06:00")).isEqualTo("2025-03-10T11:00:00Z")
    }

    @Test
    fun `ny-close is New York shifted seven hours`() {
        for (wall in listOf("2024-01-15T00:00", "2024-07-15T00:00", "2024-03-11T00:00", "2024-11-01T00:00")) {
            assertThat(utc("America/New_York+7h", wall)).isEqualTo(utc("ny-close", wall))
        }
    }

    @Test
    fun `eras switch clocks at the stated wall time`() {
        val clock = "America/New_York until 2018-12-16T17:00, then Europe/London-5h"
        assertThat(utc(clock, "2016-07-11T06:00")).isEqualTo("2016-07-11T10:00:00Z")
        assertThat(utc(clock, "2018-12-14T16:59")).isEqualTo("2018-12-14T21:59:00Z")
        assertThat(utc(clock, "2018-12-16T17:00")).isEqualTo("2018-12-16T22:00:00Z")
        assertThat(utc(clock, "2025-07-14T06:00")).isEqualTo("2025-07-14T10:00:00Z")
    }

    @Test
    fun `a clock's spec parses back to the same clock`() {
        for (text in listOf("Europe/London-5h", "Asia/Tokyo+1h", "UTC until 2020-01-01T00:00, then Europe/Athens")) {
            val clock = ImportClock.parse(text)
            assertThat(ImportClock.parse(clock.spec)).isEqualTo(clock)
        }
    }

    @Test
    fun `a time in the shifted zone's DST gap is refused, not guessed`() {
        // London's spring gap is 01:00-02:00 on 2025-03-30, which this clock writes as 20:00-21:00 the day before.
        assertThatThrownBy { utc("Europe/London-5h", "2025-03-29T20:30") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("DST gap")
    }

    @Test
    fun `malformed clocks say what is wrong`() {
        assertThatThrownBy { ImportClock.parse("Mars/Olympus-5h") }.hasMessageContaining("unknown zone")
        assertThatThrownBy { ImportClock.parse("UTC, then Europe/Athens") }.hasMessageContaining("needs 'until")
        assertThatThrownBy { ImportClock.parse("UTC until soon, then Europe/Athens") }.hasMessageContaining("date-time")
        assertThatThrownBy {
            ImportClock.parse("UTC until 2021-01-01T00:00, then Europe/Athens until 2020-01-01T00:00, then UTC")
        }.hasMessageContaining("later than")
    }
}
