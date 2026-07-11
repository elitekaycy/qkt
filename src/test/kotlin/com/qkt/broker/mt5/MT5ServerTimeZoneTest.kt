package com.qkt.broker.mt5

import java.time.Instant
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5ServerTimeZoneTest {
    @Test
    fun `new york close clock is UTC plus two in winter`() {
        val utc = Instant.parse("2026-01-15T08:00:00Z")

        assertThat(MT5ServerTimeZone.NEW_YORK_CLOSE.toServerLocal(utc))
            .isEqualTo(LocalDateTime.parse("2026-01-15T10:00:00"))
    }

    @Test
    fun `new york close clock is UTC plus three during US daylight time`() {
        val utc = Instant.parse("2026-07-15T08:00:00Z")

        assertThat(MT5ServerTimeZone.NEW_YORK_CLOSE.toServerLocal(utc))
            .isEqualTo(LocalDateTime.parse("2026-07-15T11:00:00"))
    }

    @Test
    fun `broker wall epoch converts back to true UTC`() {
        val serverWallEpoch = Instant.parse("2026-07-15T11:00:00Z").toEpochMilli()

        assertThat(MT5ServerTimeZone.NEW_YORK_CLOSE.serverEpochToUtc(serverWallEpoch))
            .isEqualTo(Instant.parse("2026-07-15T08:00:00Z").toEpochMilli())
    }

    @Test
    fun `calendar zone follows the same winter and summer offsets`() {
        val zone = MT5ServerTimeZone.NEW_YORK_CLOSE.asZoneId()

        assertThat(zone.rules.getOffset(Instant.parse("2026-01-15T08:00:00Z")).id).isEqualTo("+02:00")
        assertThat(zone.rules.getOffset(Instant.parse("2026-07-15T08:00:00Z")).id).isEqualTo("+03:00")
    }
}
