package com.qkt.dsl.compile

import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.TimeOfDay
import java.time.Instant
import java.time.LocalTime
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
internal class ScheduleRunner {
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
                    nextFireMs = computeNextFire(trigger, nowMs),
                ),
            )
        }
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
                val next = computeNextFire(reg.trigger, thisFire + 1)
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
    ): Long {
        val fromInstant = Instant.ofEpochMilli(fromMs)
        return when (trigger) {
            is ScheduleTrigger.At -> nextAt(trigger.time, fromInstant, fromMs)
            is ScheduleTrigger.EveryDay -> nextAt(trigger.time, fromInstant, fromMs)
            is ScheduleTrigger.EveryHour -> nextEveryHour(trigger.minuteOffset, fromInstant, fromMs)
            is ScheduleTrigger.EveryWeekday -> nextWeekday(trigger.time, fromInstant, fromMs)
        }
    }

    private fun nextAt(
        t: TimeOfDay,
        from: Instant,
        fromMs: Long,
    ): Long {
        val today = from.atZone(ZoneOffset.UTC).toLocalDate()
        val candidate =
            today
                .atTime(LocalTime.of(t.hour, t.minute, t.second))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        return if (candidate >= fromMs) candidate else candidate + DAY_MS
    }

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

    private fun nextWeekday(
        t: TimeOfDay,
        from: Instant,
        fromMs: Long,
    ): Long {
        var candidate = nextAt(t, from, fromMs)
        while (!isWeekday(candidate)) candidate += DAY_MS
        return candidate
    }

    private fun isWeekday(epochMs: Long): Boolean {
        val dow =
            Instant
                .ofEpochMilli(epochMs)
                .atZone(ZoneOffset.UTC)
                .dayOfWeek
                .value
        return dow in 1..5 // Mon-Fri
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScheduleRunner::class.java)
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
    }
}
