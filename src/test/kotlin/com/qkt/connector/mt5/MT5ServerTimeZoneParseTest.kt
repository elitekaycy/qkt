package com.qkt.connector.mt5

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Parsing server clocks, named zones, and the New York close clock around its two yearly shifts. */
class MT5ServerTimeZoneParseTest {
    @Test
    fun `new york close parses in any case and the zero offset is the UTC clock`() {
        assertThat(MT5ServerTimeZone.parse(" New_York_Close ")).isSameAs(MT5ServerTimeZone.NEW_YORK_CLOSE)
        assertThat(MT5ServerTimeZone.parse("Z")).isSameAs(MT5ServerTimeZone.UTC)
        assertThat(MT5ServerTimeZone.fixedOffset(0)).isSameAs(MT5ServerTimeZone.UTC)
    }

    @Test
    fun `a named zone converts both ways under its own daylight rules`() {
        val athens = MT5ServerTimeZone.parse("Europe/Athens")
        val summer = Instant.parse("2026-07-15T12:00:00Z")

        assertThat(athens.id).isEqualTo("Europe/Athens")
        assertThat(athens.toString()).isEqualTo("Europe/Athens")
        assertThat(athens.asZoneId()).isEqualTo(ZoneId.of("Europe/Athens"))
        assertThat(athens.toServerLocal(summer)).isEqualTo(LocalDateTime.parse("2026-07-15T15:00:00"))
        assertThat(
            athens.toUtc(LocalDateTime.parse("2026-01-15T14:00:00")),
        ).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"))
    }

    @Test
    fun `a fixed offset clock is named by its offset`() {
        val plusTwo = MT5ServerTimeZone.fixedOffset(2)

        assertThat(plusTwo.id).isEqualTo("+02:00")
        assertThat(plusTwo.serverEpochToUtc(7_200_000L)).isZero()
        assertThat(plusTwo.asZoneId()).isEqualTo(ZoneOffset.ofHours(2))
    }

    @Test
    fun `clocks are equal by id alone`() {
        val athens = MT5ServerTimeZone.parse("Europe/Athens")

        assertThat(athens).isEqualTo(MT5ServerTimeZone.parse("Europe/Athens"))
        assertThat(athens).hasSameHashCodeAs(MT5ServerTimeZone.parse("Europe/Athens"))
        assertThat(athens).isNotEqualTo(MT5ServerTimeZone.UTC)
        assertThat(athens.equals("Europe/Athens")).isFalse()
        assertThat(athens.equals(null)).isFalse()
    }

    @Test
    fun `the hour new york close skips in spring does not exist`() {
        // US daylight time starts 2026-03-08 07:00Z: the server clock jumps from 09:00 to 10:00.
        assertThatThrownBy { MT5ServerTimeZone.NEW_YORK_CLOSE.toUtc(LocalDateTime.parse("2026-03-08T09:30:00")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("nonexistent MT5 server time 2026-03-08T09:30 in new_york_close")
    }

    @Test
    fun `the hour new york close repeats in autumn reads as its first pass`() {
        // US daylight time ends 2026-11-01 06:00Z: the server clock falls from 09:00 back to 08:00.
        val repeated = MT5ServerTimeZone.NEW_YORK_CLOSE.toUtc(LocalDateTime.parse("2026-11-01T08:30:00"))

        assertThat(repeated).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"))
    }
}
