package com.qkt.marketdata.ingest.clock

import com.qkt.marketdata.ingest.parse.ParsedTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A file from one DST season cannot show a wrong DST rule by its weekly opens drifting, only by the hour they
 * open in. FX opens at 17:00 New York, so a summer EURUSD file read an hour off opens at 18:00 every week: right
 * for spot metals, wrong for FX. Knowing which market the file is decides it.
 */
class ClockEvidenceOpenHourTest {
    private val newYork = ZoneId.of("America/New_York")

    /** Summer weekly opens at 17:00 New York, as a source on UTC-4 in summer writes them (17:00). */
    private fun summerOpensWrittenAtMinusFour(): List<ParsedTime> =
        generateSequence(LocalDate.of(2026, 6, 7)) { it.plusWeeks(1) }
            .take(8)
            .map { sunday ->
                val instant = sunday.atTime(17, 0).atZone(newYork).toInstant()
                ParsedTime.Local(LocalDateTime.ofInstant(instant, ZoneOffset.ofHours(-4)))
            }.toList()

    @Test
    fun `an FX file whose opens read an hour late is refused`() {
        val read = ClockEvidence.of(ImportClock.parse("-05:00"), summerOpensWrittenAtMinusFour(), MarketOpen.FX)

        assertThat(read.modalHour).isEqualTo(18)
        assertThat(read.passes).isFalse()
        assertThat(read.passesStated).isFalse()
    }

    @Test
    fun `the same opens fit a spot metal, which opens at 18 New York`() {
        val read = ClockEvidence.of(ImportClock.parse("-05:00"), summerOpensWrittenAtMinusFour(), MarketOpen.METAL)

        assertThat(read.passes).isTrue()
    }

    @Test
    fun `read on the right clock the FX opens fit`() {
        val read = ClockEvidence.of(ImportClock.parse("-04:00"), summerOpensWrittenAtMinusFour(), MarketOpen.FX)

        assertThat(read.modalHour).isEqualTo(17)
        assertThat(read.passes).isTrue()
    }
}
