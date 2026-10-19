package com.qkt.marketdata.store

import java.time.LocalDate
import java.time.ZoneOffset

/** A test store of minute ticks: day -> UTC stretches `"HH:mm-HH:mm"` that hold a tick every minute. */
internal class CoverageStore {
    val days = mutableMapOf<LocalDate, List<ActivityRun>>()

    fun day(
        day: String,
        vararg stretches: String,
    ): CoverageStore {
        val d = LocalDate.parse(day)
        val start = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val times =
            stretches.flatMap { s ->
                val (a, b) =
                    s.split("-").map { hm ->
                        hm.substringBefore(":").toLong() * 60 +
                            hm.substringAfter(":").toLong()
                    }
                (a until b).map { start + it * 60_000L }
            }
        days[d] = ActivityRunBuilder.of(times.toLongArray())
        return this
    }

    /** Gold on a winter (US standard time) day: trading 23:00-22:00 UTC around the 22:00-23:00 break. */
    fun winterGold(vararg days: String) = apply { days.forEach { day(it, "00:00-22:00", "23:00-24:00") } }

    /** Gold on a summer (US daylight time) day: the break moves to 21:00-22:00 UTC. */
    fun summerGold(vararg days: String) = apply { days.forEach { day(it, "00:00-21:00", "22:00-24:00") } }
}
