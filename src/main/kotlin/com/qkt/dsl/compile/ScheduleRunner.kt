package com.qkt.dsl.compile

import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import java.time.Instant
import java.time.ZoneId

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
 * Fire model: **replay missed occurrences**. Every elapsed fire is emitted once with
 * its scheduled event-time. Live's one-second heartbeat and sparse backtest ticks
 * therefore observe the same occurrence count across quiet market gaps.
 */
class ScheduleRunner(
    /**
     * Resolver for `Timezone.BROKER` triggers. Returns the broker's effective
     * `ZoneId` for the given strategy, or `null` to indicate the broker doesn't
     * declare a server timezone offset. When null is returned (or the resolver
     * itself is null), a strategy that uses `BROKER` fails fast at registration.
     *
     * Wired by [com.qkt.app.TradingPipeline] from the live broker profile's
     * effective server time zone. Backtest leaves it null — `BROKER` shouldn't be used
     * in backtest because there's no real broker (#77 follow-up).
     */
    private val brokerZoneIdFor: ((String) -> java.time.ZoneId?)? = null,
) {
    private data class Registration(
        val strategyId: String,
        val schedule: ScheduleDecl,
        val trigger: ScheduleTrigger,
        val emit: (Long) -> Boolean,
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
        emit: () -> Unit = {},
        emitAt: ((Long) -> Boolean)? = null,
        nowMs: Long,
    ) {
        for (trigger in schedule.triggers) {
            regs.add(
                Registration(
                    strategyId = strategyId,
                    schedule = schedule,
                    trigger = trigger,
                    emit =
                        emitAt ?: {
                            emit()
                            true
                        },
                    nextFireMs = computeNextFire(trigger, nowMs, strategyId),
                ),
            )
        }
    }

    /**
     * Resolve a trigger's timezone to a `ZoneId`, dispatching `Timezone.BROKER`
     * to the wired resolver. Throws a clear error if `BROKER` is used but no
     * resolver was supplied (typical for backtest) or if the resolver returns
     * null for [strategyId] (broker profile has no server time zone).
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
                                "supplies server_time_zone.",
                        )
                resolver(strategyId)
                    ?: error(
                        "SCHEDULE timezone BROKER used by strategy '$strategyId' " +
                            "but the broker profile has no server_time_zone. Set it in " +
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
     * Emit every elapsed occurrence in chronological order.
     */
    fun tick(nowMs: Long) {
        for (reg in regs) {
            while (reg.nextFireMs <= nowMs) {
                val thisFire = reg.nextFireMs
                reg.nextFireMs = computeNextFire(reg.trigger, thisFire + 1, reg.strategyId)
                if (!reg.emit(thisFire)) {
                    while (reg.nextFireMs <= nowMs) {
                        reg.nextFireMs = computeNextFire(reg.trigger, reg.nextFireMs + 1, reg.strategyId)
                    }
                    break
                }
            }
        }
    }

    private fun computeNextFire(
        trigger: ScheduleTrigger,
        fromMs: Long,
        strategyId: String,
    ): Long {
        val fromInstant = Instant.ofEpochMilli(fromMs)
        return when (trigger) {
            is ScheduleTrigger.At ->
                ScheduleFireTimes.nextAt(
                    trigger.time,
                    resolveZoneId(trigger.tz, strategyId),
                    fromInstant,
                    fromMs,
                )
            is ScheduleTrigger.EveryDay ->
                ScheduleFireTimes.nextAt(
                    trigger.time,
                    resolveZoneId(trigger.tz, strategyId),
                    fromInstant,
                    fromMs,
                )
            is ScheduleTrigger.EveryHour -> ScheduleFireTimes.nextEveryHour(trigger.minuteOffset, fromInstant, fromMs)
            is ScheduleTrigger.EveryWeekday ->
                ScheduleFireTimes.nextWeekday(
                    trigger.time,
                    resolveZoneId(trigger.tz, strategyId),
                    fromInstant,
                    fromMs,
                )
        }
    }
}
