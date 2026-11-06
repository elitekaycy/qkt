package com.qkt.cli.fetch

import com.qkt.cli.captureCliRun
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FetchDateRangeTest {
    @Test
    fun `last N days ends yesterday and starts N days back`() {
        val today = utcToday()

        val range = resolveFetchRange(null, null, "3d")

        assertThat(range).isEqualTo(today.minusDays(3) to today.minusDays(1))
    }

    @Test
    fun `explicit dates parse and last wins over them`() {
        assertThat(resolveFetchRange("2026-07-01", "2026-07-03", null))
            .isEqualTo(LocalDate.parse("2026-07-01") to LocalDate.parse("2026-07-03"))
        assertThat(resolveFetchRange("2026-07-01", "2026-07-03", "1d")?.second).isEqualTo(utcToday().minusDays(1))
    }

    @Test
    fun `a malformed last a missing bound and an unparsable date are reported and give no range`() {
        val badLast = captureCliRun { if (resolveFetchRange(null, null, "week") == null) 1 else 0 }
        assertThat(badLast.code).isEqualTo(1)
        assertThat(badLast.err).contains("--last must be like '30d', got 'week'")

        val noTo = captureCliRun { if (resolveFetchRange("2026-07-01", null, null) == null) 1 else 0 }
        assertThat(noTo.code).isEqualTo(1)
        assertThat(noTo.err).contains("need either --from + --to or --last <Nd>")

        val badDate = captureCliRun { if (resolveFetchRange("2026-13-01", "2026-07-03", null) == null) 1 else 0 }
        assertThat(badDate.code).isEqualTo(1)
        assertThat(badDate.err).contains("invalid date in --from/--to")
    }

    @Test
    fun `the wall clock reading is the current instant`() {
        val before = Instant.now().toEpochMilli()

        val now = utcNowMs()

        assertThat(now).isBetween(before, Instant.now().toEpochMilli())
    }
}
