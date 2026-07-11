package com.qkt.broker.mt5

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules
import java.time.zone.ZoneRulesProvider
import java.util.NavigableMap
import java.util.TreeMap

/**
 * Broker-server clock used to translate MT5 wall-clock epochs at the gateway boundary.
 *
 * [NEW_YORK_CLOSE] models the common forex server clock: UTC+2 in New York standard
 * time and UTC+3 in New York daylight time. Named IANA zones and fixed offsets are
 * also supported for brokers with different clocks.
 */
class MT5ServerTimeZone private constructor(
    /** Stable configuration identifier. */
    val id: String,
    private val zoneId: ZoneId?,
    private val newYorkClose: Boolean,
) {
    /** Converts a true UTC instant to the broker's local wall-clock value. */
    fun toServerLocal(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, offsetAt(instant))

    /** Converts a broker-local wall-clock value to its true UTC instant. */
    fun toUtc(serverLocal: LocalDateTime): Instant {
        if (!newYorkClose) return serverLocal.atZone(requireNotNull(zoneId)).toInstant()
        val candidates =
            listOf(WINTER_OFFSET, SUMMER_OFFSET)
                .map { offset -> offset to serverLocal.toInstant(offset) }
                .filter { (offset, candidate) -> offsetAt(candidate) == offset }
                .map { it.second }
        require(candidates.isNotEmpty()) { "nonexistent MT5 server time $serverLocal in $id" }
        return candidates.minOrNull() ?: error("no UTC candidate for MT5 server time $serverLocal in $id")
    }

    /** Corrects an epoch whose numeric fields encode broker wall time rather than UTC. */
    fun serverEpochToUtc(epochMs: Long): Long {
        val wall = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)
        return toUtc(wall).toEpochMilli()
    }

    /** Zone id for calendar and schedule evaluation under the same server-clock rules. */
    fun asZoneId(): ZoneId = if (newYorkClose) newYorkCloseZoneId() else requireNotNull(zoneId)

    private fun offsetAt(instant: Instant): ZoneOffset =
        if (newYorkClose) {
            val nySeconds = NEW_YORK.rules.getOffset(instant).totalSeconds
            ZoneOffset.ofTotalSeconds(nySeconds + NEW_YORK_CLOSE_SHIFT_SECONDS)
        } else {
            requireNotNull(zoneId).rules.getOffset(instant)
        }

    override fun equals(other: Any?): Boolean = other is MT5ServerTimeZone && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        /** UTC clock for gateways that already expose true UTC. */
        val UTC: MT5ServerTimeZone = MT5ServerTimeZone("UTC", ZoneOffset.UTC, newYorkClose = false)

        /** Forex New York-close clock: UTC+2 in winter and UTC+3 during US DST. */
        val NEW_YORK_CLOSE: MT5ServerTimeZone =
            MT5ServerTimeZone("new_york_close", zoneId = null, newYorkClose = true)

        /** Parses `new_york_close`, an IANA zone id, or a fixed offset such as `+02:00`. */
        fun parse(value: String): MT5ServerTimeZone {
            val normalized = value.trim()
            if (normalized.equals(NEW_YORK_CLOSE.id, ignoreCase = true)) return NEW_YORK_CLOSE
            val zone = ZoneId.of(normalized)
            return if (zone == ZoneOffset.UTC) UTC else MT5ServerTimeZone(zone.id, zone, newYorkClose = false)
        }

        /** Creates a legacy fixed-offset clock. Prefer [parse] with a DST-aware identifier. */
        fun fixedOffset(hours: Int): MT5ServerTimeZone = parse(ZoneOffset.ofHours(hours).id)

        private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
        private val WINTER_OFFSET: ZoneOffset = ZoneOffset.ofHours(2)
        private val SUMMER_OFFSET: ZoneOffset = ZoneOffset.ofHours(3)
        private const val NEW_YORK_CLOSE_SHIFT_SECONDS: Int = 7 * 60 * 60

        private fun newYorkCloseZoneId(): ZoneId {
            runCatching { ZoneRulesProvider.registerProvider(NewYorkCloseRulesProvider) }
            return ZoneId.of(NewYorkCloseRulesProvider.ZONE_ID)
        }
    }
}

private object NewYorkCloseRulesProvider : ZoneRulesProvider() {
    const val ZONE_ID: String = "QKT/MT5-New_York_Close"
    private val winter = ZoneOffset.ofHours(2)
    private val summer = ZoneOffset.ofHours(3)
    private val rules =
        ZoneRules.of(
            winter,
            winter,
            emptyList(),
            listOf(
                ZoneOffsetTransition.of(LocalDateTime.of(2000, 3, 12, 9, 0), winter, summer),
                ZoneOffsetTransition.of(LocalDateTime.of(2000, 11, 5, 9, 0), summer, winter),
            ),
            listOf(
                ZoneOffsetTransitionRule.of(
                    Month.MARCH,
                    8,
                    DayOfWeek.SUNDAY,
                    LocalTime.of(9, 0),
                    false,
                    ZoneOffsetTransitionRule.TimeDefinition.WALL,
                    winter,
                    winter,
                    summer,
                ),
                ZoneOffsetTransitionRule.of(
                    Month.NOVEMBER,
                    1,
                    DayOfWeek.SUNDAY,
                    LocalTime.of(9, 0),
                    false,
                    ZoneOffsetTransitionRule.TimeDefinition.WALL,
                    winter,
                    summer,
                    winter,
                ),
            ),
        )

    override fun provideZoneIds(): MutableSet<String> = mutableSetOf(ZONE_ID)

    override fun provideRules(
        zoneId: String,
        forCaching: Boolean,
    ): ZoneRules {
        require(zoneId == ZONE_ID) { "unknown qkt MT5 zone $zoneId" }
        return rules
    }

    override fun provideVersions(zoneId: String): NavigableMap<String, ZoneRules> {
        require(zoneId == ZONE_ID) { "unknown qkt MT5 zone $zoneId" }
        return TreeMap<String, ZoneRules>().apply { put("1", rules) }
    }
}
