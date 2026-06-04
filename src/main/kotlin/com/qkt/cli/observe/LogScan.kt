package com.qkt.cli.observe

import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException

// File-level so both LogLine and LogScan share one definition.
private val LINE = Regex("""^(\S+)\s+\[(\w+)]\s+(.*)$""")
private val REALIZED = Regex("""realized=(-?\d+(?:\.\d+)?)""")

/**
 * One parsed per-strategy log line. The daemon writes the per-strategy log as
 * `<ISO8601> [<LEVEL>] <message>` (e.g. `2026-06-04T07:55:01.200 [INFO] submit Stop …`).
 */
data class LogLine(
    val timestamp: Instant,
    val level: String,
    val message: String,
) {
    /** An order was submitted (the placement signal); message begins `submit `. */
    val isSubmit: Boolean get() = message.startsWith("submit ")

    /** An engine-side error line. */
    val isError: Boolean get() = level == "ERROR"

    /** Realized P&L on a fill, parsed from a `trade … realized=<n>` line; null otherwise. */
    val realized: BigDecimal? get() =
        if (!message.startsWith("trade ")) {
            null
        } else {
            REALIZED
                .find(message)
                ?.groupValues
                ?.get(1)
                ?.toBigDecimalOrNull()
        }
}

/** Parses raw `/logs` text into [LogLine]s, skipping lines without the `<ts> [LEVEL]` prefix. */
object LogScan {
    fun parse(raw: String): List<LogLine> =
        raw
            .lineSequence()
            .mapNotNull { line ->
                val m = LINE.matchEntire(line.trim()) ?: return@mapNotNull null
                val ts =
                    try {
                        Instant.parse(normalizeTs(m.groupValues[1]))
                    } catch (e: DateTimeParseException) {
                        return@mapNotNull null
                    }
                LogLine(ts, m.groupValues[2], m.groupValues[3])
            }.toList()

    // The logback file pattern emits a local-naive ISO8601 with no zone; treat it as UTC.
    private fun normalizeTs(s: String): String = if (s.endsWith("Z")) s else "${s}Z"
}
