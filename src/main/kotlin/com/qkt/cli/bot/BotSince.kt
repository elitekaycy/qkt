package com.qkt.cli.bot

import java.time.Instant

/** `--since` accepts `Nd` (days back), an ISO instant, or epoch millis; default 7 days. */
internal fun parseSince(
    raw: String?,
    nowMs: Long,
): Long =
    when {
        raw == null -> nowMs - 7L * 86_400_000L
        raw.matches(Regex("\\d+d")) -> nowMs - raw.dropLast(1).toLong() * 86_400_000L
        raw.toLongOrNull() != null -> raw.toLong()
        else ->
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
                error("--since must be Nd, ISO-8601, or epoch millis; got '$raw'")
            }
    }
