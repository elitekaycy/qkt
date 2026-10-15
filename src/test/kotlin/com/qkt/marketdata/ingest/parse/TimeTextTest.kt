package com.qkt.marketdata.ingest.parse

import java.time.LocalDate
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeTextTest {
    private fun p(spec: String) = TimeFormat.parse(spec)

    @Test
    fun `a pattern is strict so February 30th is an error, not March 1st`() {
        assertThatThrownBy { TimeText.parse("2024.02.30 00:00:00", p("yyyy.MM.dd HH:mm:ss")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not match")
    }

    @Test
    fun `a wall-clock time stays local until a clock is applied`() {
        assertThat(TimeText.parse("2024.03.11 00:00:00", p("yyyy.MM.dd HH:mm:ss")))
            .isEqualTo(ParsedTime.Local(LocalDateTime.of(2024, 3, 11, 0, 0)))
    }

    @Test
    fun `a Dukascopy local-time row carries its own offset, so it is an instant`() {
        val parsed = TimeText.parse("10.03.2024 18:00:00.000 GMT-0400", p("dd.MM.yyyy HH:mm:ss.SSS 'GMT'xx"))

        assertThat(parsed).isEqualTo(ParsedTime.Instant(1_710_108_000_000L))
    }

    @Test
    fun `HistData glues milliseconds to the seconds`() {
        val parsed = TimeText.parse("20191201 170003296", p("yyyyMMdd HHmmssSSS"))

        assertThat(parsed).isEqualTo(ParsedTime.Local(LocalDateTime.of(2019, 12, 1, 17, 0, 3, 296_000_000)))
    }

    @Test
    fun `ISO times with an offset, a Z, a space or nine fraction digits are all instants`() {
        assertThat(
            TimeText.parse("2024-01-02 09:30:00-05:00", TimeFormat.Iso),
        ).isEqualTo(ParsedTime.Instant(1_704_205_800_000L))
        assertThat(
            TimeText.parse("2016-10-17T19:35:00.000000000Z", TimeFormat.Iso),
        ).isEqualTo(ParsedTime.Instant(1_476_732_900_000L))
        assertThat(
            TimeText.parse("2016-10-17T19:35:00.000000500Z", TimeFormat.Iso),
        ).isEqualTo(ParsedTime.Instant(1_476_732_900_000L, true))
    }

    @Test
    fun `a bare date is a session day, not an instant`() {
        assertThat(TimeText.parse("2008-01-02", TimeFormat.Iso)).isEqualTo(ParsedTime.Date(LocalDate.of(2008, 1, 2)))
    }

    @Test
    fun `an epoch needs its unit resolved first`() {
        assertThat(
            TimeText.parse("1590969600000", TimeFormat.Epoch(EpochUnit.MILLIS)),
        ).isEqualTo(ParsedTime.Instant(1_590_969_600_000L))
        assertThatThrownBy {
            TimeText.parse("abc", TimeFormat.Epoch(EpochUnit.MILLIS))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `format specs name every kind`() {
        assertThat(p("epoch")).isEqualTo(TimeFormat.Epoch(null))
        assertThat(p("epoch:us")).isEqualTo(TimeFormat.Epoch(EpochUnit.MICROS))
        assertThat(p("iso")).isEqualTo(TimeFormat.Iso)
        assertThat(TimeFormat.Epoch(EpochUnit.NANOS).spec).isEqualTo("epoch:ns")
        assertThatThrownBy { p("epoch:weeks") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
