package com.qkt.connector.mt5

import java.time.Instant
import java.time.ZoneOffset
import java.time.zone.ZoneRulesProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The New York close server clock is published to java.time as one zone with one rules version. */
class MT5NewYorkCloseZoneTest {
    @Test
    fun `the registered zone offers a single version of its rules, UTC+3 in summer and UTC+2 in winter`() {
        val zone = MT5ServerTimeZone.NEW_YORK_CLOSE.asZoneId()

        val versions = ZoneRulesProvider.getVersions(zone.id)

        assertThat(versions.keys).containsExactly("1")
        val rules = versions.getValue("1")
        assertThat(rules.getOffset(Instant.parse("2026-07-15T12:00:00Z"))).isEqualTo(ZoneOffset.ofHours(3))
        assertThat(rules.getOffset(Instant.parse("2026-01-15T12:00:00Z"))).isEqualTo(ZoneOffset.ofHours(2))
    }
}
