package com.qkt.dsl.ast

import java.math.BigDecimal

data class StrategyAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val constants: List<ConstantDecl>,
    val lets: List<LetDecl>,
    val defaults: DefaultsBlock?,
    val rules: List<RuleAst>,
    val syncGroups: List<SyncGroupDecl> = emptyList(),
    val schedules: List<ScheduleDecl> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "StrategyAst.name must not be blank" }
        require(version >= 0) { "StrategyAst.version must be >= 0: $version" }
    }
}

data class StreamDecl(
    val alias: String,
    val broker: String,
    val symbol: String,
    val timeframe: String,
    val warmupBars: Int? = null,
) {
    init {
        require(alias.isNotBlank()) { "StreamDecl.alias must not be blank" }
        require(broker.isNotBlank()) { "StreamDecl.broker must not be blank" }
        require(symbol.isNotBlank()) { "StreamDecl.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "StreamDecl.timeframe must not be blank" }
        if (warmupBars != null) require(warmupBars > 0) { "StreamDecl.warmupBars must be > 0 if set: $warmupBars" }
    }

    val qktSymbol: String get() = "$broker:$symbol"
}

data class ConstantDecl(
    val name: String,
    val value: BigDecimal,
) {
    init {
        require(name.isNotBlank()) { "ConstantDecl.name must not be blank" }
    }
}

data class LetDecl(
    val name: String,
    val expr: ExprAst,
) {
    init {
        require(name.isNotBlank()) { "LetDecl.name must not be blank" }
    }
}

/**
 * Time of day for a `SCHEDULE` trigger. `09:00` parses to `TimeOfDay(9, 0)`;
 * `19:55:30` parses to `TimeOfDay(19, 55, 30)`.
 *
 * e.g. inside `SCHEDULE AT 09:00 UTC THEN BUY gold ...`, the `09:00` half
 * lands as `TimeOfDay(hour = 9, minute = 0, second = 0)`.
 *
 * Range checks at construction prevent garbage like `25:00` from reaching the engine.
 */
data class TimeOfDay(
    val hour: Int,
    val minute: Int,
    val second: Int = 0,
) {
    init {
        require(hour in 0..23) { "TimeOfDay.hour must be 0-23: $hour" }
        require(minute in 0..59) { "TimeOfDay.minute must be 0-59: $minute" }
        require(second in 0..59) { "TimeOfDay.second must be 0-59: $second" }
    }
}

/**
 * Timezone tag on a `SCHEDULE` trigger. Resolves to a `java.time.ZoneId` so the
 * runner does DST-correct fire-time math.
 *
 * Why explicit per-trigger required: silent timezone defaults caused enough
 * live-trading bugs in pa-quant to justify forcing strategy authors to write
 * the suffix. `UTC` is the safe default for crypto/instrument-agnostic
 * scheduling; named zones are for session-anchored strategies.
 *
 *  - `UTC` — system standard, no DST
 *  - `NY` — America/New_York, DST-shifts twice a year, anchors NY equity / FX session
 *  - `LONDON` — Europe/London, DST, anchors LDN FX session (typical volume peak)
 *  - `TOKYO` — Asia/Tokyo, no DST, anchors Asia session and JPY pairs
 *  - `SYDNEY` — Australia/Sydney, DST (southern hemisphere — opposite phase), AUD pairs
 *  - `CHICAGO` — America/Chicago, DST, anchors CME futures sessions (energy, ag)
 *  - `BROKER` — reserved; resolves to the broker profile's `serverTzOffset` from
 *    `qkt.config.yaml`. Not yet wired through — strategies that use `BROKER` fail at
 *    `bindToHub` time until the broker-profile plumbing lands. See spec
 *    `docs/superpowers/specs/2026-05-31-phase40-schedule-design.md` for the deferred work.
 */
sealed interface Timezone {
    /** `java.time.ZoneId` for date/time math. `BROKER` throws — see [resolveZoneId]. */
    val zoneId: java.time.ZoneId

    data object UTC : Timezone {
        override val zoneId: java.time.ZoneId = java.time.ZoneOffset.UTC
    }

    data object NY : Timezone {
        override val zoneId: java.time.ZoneId = java.time.ZoneId.of("America/New_York")
    }

    data object LONDON : Timezone {
        override val zoneId: java.time.ZoneId = java.time.ZoneId.of("Europe/London")
    }

    data object TOKYO : Timezone {
        override val zoneId: java.time.ZoneId = java.time.ZoneId.of("Asia/Tokyo")
    }

    data object SYDNEY : Timezone {
        override val zoneId: java.time.ZoneId = java.time.ZoneId.of("Australia/Sydney")
    }

    data object CHICAGO : Timezone {
        override val zoneId: java.time.ZoneId = java.time.ZoneId.of("America/Chicago")
    }

    data object BROKER : Timezone {
        override val zoneId: java.time.ZoneId
            get() =
                error(
                    "SCHEDULE timezone BROKER is reserved but not yet wired to a broker profile. " +
                        "Use UTC or a named IANA zone (NY/LONDON/TOKYO/SYDNEY/CHICAGO) until " +
                        "broker profile serverTzOffset config ships.",
                )
    }
}

/**
 * One trigger inside a `SCHEDULE` clause. A clause can carry several triggers
 * (the `AT 09:00, 12:00, 14:00 UTC` list form parses to three `At` instances),
 * and a strategy can declare many clauses.
 *
 * See `docs/superpowers/specs/2026-05-31-phase40-schedule-design.md` (#77).
 */
sealed interface ScheduleTrigger {
    /** One-off at the given time of day, fires daily — e.g. every day at 09:00 UTC. */
    data class At(
        val time: TimeOfDay,
        val tz: Timezone,
    ) : ScheduleTrigger

    /**
     * Every hour at the given minute past the hour (0-59).
     * e.g. `EveryHour(30)` fires at 00:30, 01:30, 02:30 … in any timezone (the
     * offset is per-hour wall clock so no tz tag is needed).
     */
    data class EveryHour(
        val minuteOffset: Int,
    ) : ScheduleTrigger {
        init {
            require(minuteOffset in 0..59) {
                "EveryHour.minuteOffset must be 0-59: $minuteOffset"
            }
        }
    }

    /** Once per day at the given time. */
    data class EveryDay(
        val time: TimeOfDay,
        val tz: Timezone,
    ) : ScheduleTrigger

    /** Monday-Friday only per the strategy's `TradingCalendar`, at the given time. */
    data class EveryWeekday(
        val time: TimeOfDay,
        val tz: Timezone,
    ) : ScheduleTrigger
}

/**
 * One `SCHEDULE` clause: a list of triggers and the action body to run on each fire.
 *
 * e.g. `SCHEDULE AT 09:00, 12:00, 14:00 UTC THEN LOG "checkpoint"` parses to
 * one `ScheduleDecl` with three `At` triggers and the `Log` action.
 */
data class ScheduleDecl(
    val triggers: List<ScheduleTrigger>,
    val action: ActionAst,
) {
    init {
        require(triggers.isNotEmpty()) {
            "ScheduleDecl needs at least one trigger"
        }
    }
}

/**
 * One declared sync group inside a `SYMBOLS` block. The engine evaluates the
 * strategy once per group-bar-window, with every member's bar in scope atomically.
 *
 * e.g. `SYNCHRONIZE gold silver WITHIN 200ms` parses to
 * `SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = 200)`.
 *
 * See `docs/superpowers/specs/2026-05-30-phase35-bar-sync-design.md` (#45).
 */
data class SyncGroupDecl(
    val aliases: List<String>,
    val timeoutMs: Long? = null,
) {
    init {
        require(aliases.size >= 2) {
            "SyncGroupDecl needs at least 2 aliases, got ${aliases.size}"
        }
        require(timeoutMs == null || timeoutMs > 0) {
            "SyncGroupDecl.timeoutMs must be positive when present: $timeoutMs"
        }
        require(aliases.toSet().size == aliases.size) {
            "SyncGroupDecl aliases must be unique: $aliases"
        }
    }
}
