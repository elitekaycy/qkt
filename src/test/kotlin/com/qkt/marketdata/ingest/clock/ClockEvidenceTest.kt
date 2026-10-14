package com.qkt.marketdata.ingest.clock

import com.qkt.marketdata.ingest.parse.ParsedTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClockEvidenceTest {
    private val newYork = ZoneId.of("America/New_York")

    /** A broker's server-clock weekly opens: each Sunday's 17:00 New York, written on [clock]. */
    private fun opensWrittenOn(clock: ImportClock): List<ParsedTime> =
        generateSequence(LocalDate.of(2024, 1, 7)) { it.plusWeeks(1) }
            .take(52)
            .map { sunday ->
                val instant = sunday.atTime(17, 0).atZone(newYork).toInstant()
                val wall = LocalDateTime.ofInstant(instant, clock.rules.getOffset(instant))
                ParsedTime.Local(wall.plusHours(clock.shiftHours))
            }.toList()

    @Test
    fun `a ny-close export proves ny-close and only ny-close among the candidates`() {
        val opens = opensWrittenOn(ImportClock.NyClose)
        val fitting =
            ClockEvidence.CANDIDATES
                .map {
                    ClockEvidence.of(
                        it,
                        opens,
                    )
                }.filter { it.passes && it.spansSeasons }

        assertThat(fitting.map { it.clock }).containsExactly(ImportClock.NyClose)
    }

    @Test
    fun `reading a ny-close export as Europe-Athens shows in the US-EU mismatch weeks`() {
        val evidence = ClockEvidence.of(ImportClock.parse("Europe/Athens"), opensWrittenOn(ImportClock.NyClose))

        assertThat(evidence.passes).isFalse()
        assertThat(evidence.outliers).isNotEmpty().allMatch { it.usSummer != it.euSummer }
    }

    @Test
    fun `reading a DST-switching broker as a fixed offset shifts every summer week`() {
        val evidence = ClockEvidence.of(ImportClock.parse("+02:00"), opensWrittenOn(ImportClock.NyClose))

        assertThat(evidence.passes).isFalse()
        assertThat(evidence.summary()).contains("52 weekly opens")
    }

    @Test
    fun `a fixed EST file such as HistData is consistent on its own clock`() {
        val evidence = ClockEvidence.of(ImportClock.parse("-05:00"), opensWrittenOn(ImportClock.parse("-05:00")))

        assertThat(evidence.passes).isTrue()
        assertThat(evidence.modalHour).isEqualTo(17)
    }

    @Test
    fun `an open far from the New York rollover fails even when consistent`() {
        val evidence = ClockEvidence.of(ImportClock.Utc, opensWrittenOn(ImportClock.parse("+09:00")))

        assertThat(evidence.passes).isFalse()
    }

    @Test
    fun `weekly opens are found from file order in either direction`() {
        val times =
            listOf("2024-03-08T21:58", "2024-03-08T21:59", "2024-03-10T22:00", "2024-03-10T22:01")
                .map { ParsedTime.Local(LocalDateTime.parse(it)) }
        val forward = WeeklyOpens().also { o -> times.forEach(o::add) }
        val backward = WeeklyOpens().also { o -> times.reversed().forEach(o::add) }

        assertThat(forward.all).containsExactly(times[2])
        assertThat(backward.all).containsExactly(times[2])
    }
}
