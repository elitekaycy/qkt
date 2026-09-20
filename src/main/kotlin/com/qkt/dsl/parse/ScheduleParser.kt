package com.qkt.dsl.parse

import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.TimeOfDay
import com.qkt.dsl.ast.Timezone

/**
 * Parses the `SCHEDULE` block: clock-triggered actions (`AT 09:30 NY THEN ...`,
 * `EVERY HOUR AT :05 THEN ...`). Every time of day must name its timezone, so a schedule never
 * silently depends on the host's zone.
 */
internal class ScheduleParser(
    private val cursor: TokenCursor,
    private val actionParser: ActionParser,
) {
    /**
     * Parse one `SCHEDULE` block (#77). Each clause is one of:
     *   - `AT <time> UTC THEN <action>` — single one-off, fires daily
     *   - `AT <t1>, <t2>, … UTC THEN <action>` — same action at multiple times
     *   - `EVERY HOUR AT :<min> THEN <action>`
     *   - `EVERY DAY AT <time> UTC THEN <action>`
     *   - `EVERY WEEKDAY AT <time> UTC THEN <action>`
     *
     * UTC is required on every `AT <time>` form. Block continues until a non-trigger
     * token (typically `RULES`).
     */
    fun parseSchedules(): List<ScheduleDecl> {
        cursor.expect(TokenKind.SCHEDULE, "expected SCHEDULE")
        val out = mutableListOf<ScheduleDecl>()
        while (cursor.peek().kind == TokenKind.AT || cursor.peek().kind == TokenKind.EVERY) {
            val triggers = mutableListOf<ScheduleTrigger>()
            if (cursor.peek().kind == TokenKind.AT) {
                cursor.advance() // AT
                val times = mutableListOf<TimeOfDay>()
                times.add(parseTimeOfDay())
                while (cursor.peek().kind == TokenKind.COMMA) {
                    cursor.advance()
                    times.add(parseTimeOfDay())
                }
                val tz = parseTimezone("SCHEDULE AT")
                for (t in times) {
                    triggers.add(ScheduleTrigger.At(time = t, tz = tz))
                }
            } else {
                triggers.add(parseScheduleTrigger())
            }
            cursor.expect(TokenKind.THEN, "expected THEN after SCHEDULE trigger(s)")
            val action = actionParser.parseAction()
            out.add(ScheduleDecl(triggers = triggers, action = action))
        }
        return out
    }

    /** Parse one non-list trigger: `EVERY HOUR AT :NN`, `EVERY DAY AT ...`, `EVERY WEEKDAY AT ...`. */
    private fun parseScheduleTrigger(): ScheduleTrigger {
        cursor.expect(TokenKind.EVERY, "expected EVERY")
        return when (cursor.peek().kind) {
            TokenKind.HOUR -> {
                cursor.advance()
                cursor.expect(TokenKind.AT, "expected AT after EVERY HOUR")
                cursor.expect(TokenKind.COLON, "expected ':' before minute offset")
                val mTok = cursor.expect(TokenKind.NUMBER, "expected minute 0-59")
                val m = mTok.lexeme.toIntOrNull() ?: cursor.error("expected integer minute, got '${mTok.lexeme}'")
                ScheduleTrigger.EveryHour(minuteOffset = m)
            }
            TokenKind.DAY -> {
                cursor.advance()
                cursor.expect(TokenKind.AT, "expected AT after EVERY DAY")
                val time = parseTimeOfDay()
                val tz = parseTimezone("EVERY DAY")
                ScheduleTrigger.EveryDay(time = time, tz = tz)
            }
            TokenKind.WEEKDAY -> {
                cursor.advance()
                cursor.expect(TokenKind.AT, "expected AT after EVERY WEEKDAY")
                val time = parseTimeOfDay()
                val tz = parseTimezone("EVERY WEEKDAY")
                ScheduleTrigger.EveryWeekday(time = time, tz = tz)
            }
            else -> cursor.error("expected HOUR, DAY, or WEEKDAY after EVERY, got '${cursor.peek().lexeme}'")
        }
    }

    /**
     * Parse the timezone tag that follows a time literal in a `SCHEDULE` trigger.
     * One of `UTC` / `NY` / `LONDON` / `TOKYO` / `SYDNEY` / `CHICAGO` / `BROKER`.
     * [where] is the trigger label used in the error message.
     */
    private fun parseTimezone(where: String): Timezone =
        when (cursor.peek().kind) {
            TokenKind.UTC -> {
                cursor.advance()
                Timezone.UTC
            }
            TokenKind.NY -> {
                cursor.advance()
                Timezone.NY
            }
            TokenKind.LONDON -> {
                cursor.advance()
                Timezone.LONDON
            }
            TokenKind.TOKYO -> {
                cursor.advance()
                Timezone.TOKYO
            }
            TokenKind.SYDNEY -> {
                cursor.advance()
                Timezone.SYDNEY
            }
            TokenKind.CHICAGO -> {
                cursor.advance()
                Timezone.CHICAGO
            }
            TokenKind.BROKER -> {
                cursor.advance()
                Timezone.BROKER
            }
            else ->
                cursor.error(
                    "$where requires an explicit timezone " +
                        "(UTC/NY/LONDON/TOKYO/SYDNEY/CHICAGO/BROKER), got '${cursor.peek().lexeme}'",
                )
        }

    /**
     * Parse `HH:MM` or `HH:MM:SS` into a [TimeOfDay]. Out-of-range values are
     * rejected by [TimeOfDay.init].
     */
    private fun parseTimeOfDay(): TimeOfDay {
        val hourTok = cursor.expect(TokenKind.NUMBER, "expected hour")
        cursor.expect(TokenKind.COLON, "expected ':' after hour")
        val minTok = cursor.expect(TokenKind.NUMBER, "expected minute")
        val second: Int =
            if (cursor.peek().kind == TokenKind.COLON) {
                cursor.advance()
                val secTok = cursor.expect(TokenKind.NUMBER, "expected second")
                secTok.lexeme.toIntOrNull() ?: cursor.error("expected integer second, got '${secTok.lexeme}'")
            } else {
                0
            }
        return TimeOfDay(
            hour = hourTok.lexeme.toIntOrNull() ?: cursor.error("expected integer hour, got '${hourTok.lexeme}'"),
            minute = minTok.lexeme.toIntOrNull() ?: cursor.error("expected integer minute, got '${minTok.lexeme}'"),
            second = second,
        )
    }
}
