package com.qkt.research

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A parsed research-REPL command. [parse] maps a typed line to one of these; anything
 * unrecognised becomes [Unknown] so the loop can report it instead of crashing.
 */
sealed interface ReplayCommand {
    /** Advance to the end of the feed. */
    data object Run : ReplayCommand

    /** Advance [n] bars (primary timeframe), or [n] ticks when the strategy has no timeframe. */
    data class StepBars(val n: Int) : ReplayCommand

    /** Advance by a wall-clock span of [millis]. */
    data class StepDuration(val millis: Long) : ReplayCommand

    /** Advance until the engine clock reaches [epochMillis] (reset-and-forward if in the past). */
    data class RunToTime(val epochMillis: Long) : ReplayCommand

    /** Advance until the next fill. */
    data object RunToNextTrade : ReplayCommand

    /** Restart the replay from the first tick, same strategy. */
    data object Reset : ReplayCommand

    /** Re-read and recompile the strategy file, then reset. */
    data object Reload : ReplayCommand

    /** Print the current footer without advancing. */
    data object Show : ReplayCommand

    /** Exit the session. */
    data object Quit : ReplayCommand

    /** Unrecognised input; [input] is the trimmed line. */
    data class Unknown(val input: String) : ReplayCommand

    companion object {
        /** Parse one REPL line. Never throws — bad input returns [Unknown]. */
        fun parse(line: String): ReplayCommand {
            val t = line.trim()
            if (t.isEmpty()) return Unknown("")
            val parts = t.split(Regex("\\s+"))
            return when (parts[0]) {
                "run" -> Run
                "run-to" -> parseRunTo(t, parts.drop(1))
                "step" -> parseStep(t, parts.getOrNull(1))
                "reset" -> Reset
                "reload" -> Reload
                "show" -> Show
                "quit", "exit", "q" -> Quit
                else -> Unknown(t)
            }
        }

        private fun parseRunTo(raw: String, args: List<String>): ReplayCommand {
            val arg = args.firstOrNull() ?: return Unknown(raw)
            if (arg == "next-trade") return RunToNextTrade
            val millis = parseInstantMillis(arg) ?: return Unknown(raw)
            return RunToTime(millis)
        }

        private fun parseStep(raw: String, arg: String?): ReplayCommand {
            if (arg == null) return Unknown(raw)
            arg.toIntOrNull()?.let { return StepBars(it) }
            val m = Regex("^(\\d+)([smhd])$").matchEntire(arg) ?: return Unknown(raw)
            val n = m.groupValues[1].toLong()
            val unitMs =
                when (m.groupValues[2]) {
                    "s" -> 1_000L
                    "m" -> 60_000L
                    "h" -> 3_600_000L
                    "d" -> 86_400_000L
                    else -> return Unknown(raw)
                }
            return StepDuration(n * unitMs)
        }

        private fun parseInstantMillis(s: String): Long? =
            try {
                if (s.contains('T')) {
                    Instant.parse(if (s.endsWith("Z")) s else "${s}Z").toEpochMilli()
                } else {
                    LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
            } catch (e: java.time.format.DateTimeParseException) {
                null
            }
    }
}
