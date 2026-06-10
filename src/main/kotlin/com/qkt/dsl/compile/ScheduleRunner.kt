package com.qkt.dsl.compile

import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.TimeOfDay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Clock-driven runner for `SCHEDULE` blocks (#77 Phase 40).
 *
 * Each registered trigger carries a `nextFireMs` watermark. [tick] is called
 * once per ingest from `TradingPipeline.ingest(tick)` and once per second from
 * a `LiveSession` timer (live only — for quiet markets); it advances watermarks
 * and emits the action lambda when due.
 *
 * Backtest determinism: the runner reads time only via the [tick] argument —
 * the same simulated clock that drives the rest of the engine — so a backtest
 * of a strategy with `SCHEDULE AT 09:00 UTC` fires the action at the same
 * simulated instant on every run, regardless of wall clock.
 *
 * Fire model: **skip-on-miss**. If multiple fire times elapse between calls
 * (engine paused, restarted, quiet market longer than the trigger interval),
 * the runner advances the watermark past all missed fires, logs a WARN per
 * skipped fire, and emits the action exactly once. Strategies that need
 * catch-up should detect via `NOW` and decide themselves.
 */
class ScheduleRunner(
    /**
     * Resolver for `Timezone.BROKER` triggers. Returns the broker's effective
     * `ZoneId` for the given strategy, or `null` to indicate the broker doesn't
     * declare a server timezone offset. When null is returned (or the resolver
     * itself is null), a strategy that uses `BROKER` fails fast at registration.
     *
     * Wired by [com.qkt.app.TradingPipeline] from the live broker profile's
     * `serverTzOffsetHours`. Backtest leaves it null — `BROKER` shouldn't be used
     * in backtest because there's no real broker (#77 follow-up).
     */
    private val brokerZoneIdFor: ((String) -> java.time.ZoneId?)? = null,
) {
    private data class Registration(
        val strategyId: String,
        val schedule: ScheduleDecl,
        val trigger: ScheduleTrigger,
        val emit: () -> Unit,
        var nextFireMs: Long,
    )

    private val regs: MutableList<Registration> = mutableListOf()

    /**
     * Register every trigger inside [schedule] under [strategyId]. [nowMs] is
     * the engine's current clock at registration time — it seeds the watermark
     * so the first eligible fire is the next one after registration.
     */
    fun register(
        strategyId: String,
        schedule: ScheduleDecl,
        emit: () -> Unit,
        nowMs: Long,
    ) {
        for (trigger in schedule.triggers) {
            regs.add(
                Registration(
                    strategyId = strategyId,
                    schedule = schedule,
                    trigger = trigger,
                    emit = emit,
                    nextFireMs = computeNextFire(trigger, nowMs, strategyId),
                ),
            )
        }
    }

    /**
     * Resolve a trigger's timezone to a `ZoneId`, dispatching `Timezone.BROKER`
     * to the wired resolver. Throws a clear error if `BROKER` is used but no
     * resolver was supplied (typical for backtest) or if the resolver returns
     * null for [strategyId] (broker profile has no `serverTzOffsetHours`).
     */
    private fun resolveZoneId(
        tz: com.qkt.dsl.ast.Timezone,
        strategyId: String,
    ): ZoneId =
        when (tz) {
            is com.qkt.dsl.ast.Timezone.BROKER -> {
                val resolver =
                    brokerZoneIdFor
                        ?: error(
                            "SCHEDULE timezone BROKER used by strategy '$strategyId' " +
                                "but no broker-zone resolver is configured on this pipeline. " +
                                "BROKER is only available in live mode where a broker profile " +
                                "supplies serverTzOffsetHours.",
                        )
                resolver(strategyId)
                    ?: error(
                        "SCHEDULE timezone BROKER used by strategy '$strategyId' " +
                            "but the broker profile has no serverTzOffsetHours. Set it in " +
                            "the broker config or use a named IANA timezone " +
                            "(UTC/NY/LONDON/TOKYO/SYDNEY/CHICAGO) instead.",
                    )
            }
            else -> tz.zoneId
        }

    /** Drop every trigger attributed to [strategyId]. Idempotent. */
    fun unregister(strategyId: String) {
        regs.removeAll { it.strategyId == strategyId }
    }

    /** Total trigger count across all strategies. Test/debug helper. */
    fun triggerCount(): Int = regs.size

    /**
     * Heartbeat. For each registered trigger:
     *   - while [nowMs] has crossed `nextFireMs`, advance the watermark past
     *     every fire that's elapsed;
     *   - if more than one fire elapsed in one heartbeat call, emit one WARN
     *     per skipped fire;
     *   - emit the action exactly once at the most recent eligible time.
     */
    fun tick(nowMs: Long) {
        for (reg in regs) {
            if (nowMs < reg.nextFireMs) continue
            var fired = false
            while (reg.nextFireMs <= nowMs) {
                val thisFire = reg.nextFireMs
                val next = computeNextFire(reg.trigger, thisFire + 1, reg.strategyId)
                if (fired) {
                    log.warn(
                        "schedule for strategy={} missed fire at {} ({}ms behind heartbeat)",
                        reg.strategyId,
                        Instant.ofEpochMilli(thisFire),
                        nowMs - thisFire,
                    )
                }
                reg.nextFireMs = next
                if (!fired) fired = true
            }
            if (fired) reg.emit()
        }
    }

    private fun computeNextFire(
        trigger: ScheduleTrigger,
        fromMs: Long,
        strategyId: String,
    ): Long {
        val fromInstant = Instant.ofEpochMilli(fromMs)
        return when (trigger) {
            is ScheduleTrigger.At -> nextAt(trigger.time, resolveZoneId(trigger.tz, strategyId), fromInstant, fromMs)
            is ScheduleTrigger.EveryDay ->
                nextAt(
                    trigger.time,
                    resolveZoneId(trigger.tz, strategyId),
                    fromInstant,
                    fromMs,
                )
            is ScheduleTrigger.EveryHour -> nextEveryHour(trigger.minuteOffset, fromInstant, fromMs)
            is ScheduleTrigger.EveryWeekday ->
                nextWeekday(
                    trigger.time,
                    resolveZoneId(trigger.tz, strategyId),
                    fromInstant,
                    fromMs,
                )
        }
    }

    /**
     * Resolve next-fire for a time-of-day in a named zone. `today` is the local
     * calendar date in [zone]; the candidate is that date at the local clock
     * time the trigger declared, converted to UTC epoch ms. Handles DST
     * correctly because the zone resolution does — e.g. NY 09:00 is a different
     * UTC instant in March vs. November.
     */
    private fun nextAt(
        t: TimeOfDay,
        zone: ZoneId,
        from: Instant,
        fromMs: Long,
    ): Long {
        val time = LocalTime.of(t.hour, t.minute, t.second)
        val todayLocal = from.atZone(zone).toLocalDate()
        val candidate = localInstantMs(todayLocal, time, zone)
        // Roll to the NEXT LOCAL DAY by date arithmetic, never by adding 24h of epoch
        // millis: across a DST transition the same local clock time is a different
        // UTC offset, and a fixed-day add fires an hour early or late.
        return if (candidate >= fromMs) candidate else localInstantMs(todayLocal.plusDays(1), time, zone)
    }

    private fun localInstantMs(
        date: java.time.LocalDate,
        time: LocalTime,
        zone: ZoneId,
    ): Long =
        date
            .atTime(time)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun nextEveryHour(
        minuteOffset: Int,
        from: Instant,
        fromMs: Long,
    ): Long {
        val thisHour =
            from
                .atZone(ZoneOffset.UTC)
                .withMinute(minuteOffset)
                .withSecond(0)
                .withNano(0)
                .toInstant()
                .toEpochMilli()
        return if (thisHour >= fromMs) thisHour else thisHour + HOUR_MS
    }

    /**
     * Mon-Fri only, evaluated in the trigger's local [zone]. A weekend day in
     * London might still be a Friday in Tokyo at the same UTC instant — so the
     * weekday check uses the local calendar date, not UTC.
     */
    private fun nextWeekday(
        t: TimeOfDay,
        zone: ZoneId,
        from: Instant,
        fromMs: Long,
    ): Long {
        val time = LocalTime.of(t.hour, t.minute, t.second)
        var date = from.atZone(zone).toLocalDate()
        var candidate = localInstantMs(date, time, zone)
        if (candidate < fromMs) {
            date = date.plusDays(1)
            candidate = localInstantMs(date, time, zone)
        }
        // Local-date arithmetic for the same DST reason as [nextAt].
        while (!isWeekday(candidate, zone)) {
            date = date.plusDays(1)
            candidate = localInstantMs(date, time, zone)
        }
        return candidate
    }

    private fun isWeekday(
        epochMs: Long,
        zone: ZoneId,
    ): Boolean {
        val dow =
            Instant
                .ofEpochMilli(epochMs)
                .atZone(zone)
                .dayOfWeek
                .value
        return dow in 1..5 // Mon-Fri
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScheduleRunner::class.java)
        private const val HOUR_MS = 3_600_000L
    }
}
