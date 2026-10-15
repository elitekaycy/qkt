package com.qkt.marketdata.ingest.parse

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle

/**
 * How a source writes a timestamp: `epoch` (unit decided per file), `epoch:s|ms|us|ns`, `iso`
 * (ISO-8601, with or without an offset, `T` or a space between date and time), or a `java.time`
 * pattern such as `yyyy.MM.dd HH:mm:ss` or `dd.MM.yyyy HH:mm:ss.SSS 'GMT'xx`. Patterns are strict: every
 * field must be present and in range, so `2024.02.30` is an error rather than March 1st.
 */
sealed interface TimeFormat {
    /** The text form, as a preset or `--time-format` writes it. */
    val spec: String

    data class Epoch(
        val unit: EpochUnit?,
    ) : TimeFormat {
        override val spec: String
            get() = unit?.let { "epoch:${UNIT_NAMES.entries.first { e -> e.value == it }.key}" } ?: "epoch"
    }

    data object Iso : TimeFormat {
        override val spec: String = "iso"
    }

    data class Pattern(
        override val spec: String,
    ) : TimeFormat {
        /** The strict formatter for [spec]; `y` is read as the proleptic year `u`, as STRICT requires. */
        val formatter: DateTimeFormatter =
            DateTimeFormatterBuilder()
                .appendPattern(prolepticYears(spec))
                .toFormatter()
                .withResolverStyle(ResolverStyle.STRICT)
    }

    companion object {
        private val UNIT_NAMES =
            mapOf("s" to EpochUnit.SECONDS, "ms" to EpochUnit.MILLIS, "us" to EpochUnit.MICROS, "ns" to EpochUnit.NANOS)

        /** The format [text] names; an invalid pattern fails naming it. */
        fun parse(text: String): TimeFormat {
            val t = text.trim()
            return when {
                t == "epoch" -> Epoch(null)
                t.startsWith("epoch:") ->
                    Epoch(
                        UNIT_NAMES[t.removePrefix("epoch:")]
                            ?: throw IllegalArgumentException("unknown epoch unit in '$t' (expected s, ms, us or ns)"),
                    )
                t == "iso" -> Iso
                else ->
                    try {
                        Pattern(t)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("invalid time pattern '$t': ${e.message}")
                    }
            }
        }

        /** [pattern] with unquoted `y` letters turned into `u`, leaving quoted literals alone. */
        internal fun prolepticYears(pattern: String): String {
            val out = StringBuilder(pattern.length)
            var quoted = false
            for (c in pattern) {
                if (c == '\'') quoted = !quoted
                out.append(if (!quoted && c == 'y') 'u' else c)
            }
            return out.toString()
        }
    }
}
