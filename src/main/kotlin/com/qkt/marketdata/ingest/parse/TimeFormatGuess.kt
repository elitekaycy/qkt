package com.qkt.marketdata.ingest.parse

/**
 * Decides how one file writes its timestamps from a sample of them. A stated format is only completed
 * (an `epoch` column gets its unit from the magnitudes); a missing one is guessed, and the guess must be
 * unique: every candidate pattern is tried strictly on every sample, and when two survive (`03/04/2024`
 * reads as March or April) the import refuses rather than pick one.
 */
object TimeFormatGuess {
    private val PATTERNS =
        listOf(
            "yyyy.MM.dd HH:mm:ss.SSS",
            "yyyy.MM.dd HH:mm:ss",
            "yyyy.MM.dd HH:mm",
            "yyyy.MM.dd",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
            "dd.MM.yyyy HH:mm:ss.SSS",
            "dd.MM.yyyy HH:mm:ss",
            "dd.MM.yyyy HH:mm",
            "dd.MM.yyyy",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm",
            "MM/dd/yyyy",
            "MM/dd/yyyy hh:mm:ss a",
            "MM/dd/yyyy hh:mm a",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy",
            "yyyyMMdd HHmmssSSS",
            "yyyyMMdd HHmmss",
            "yyyyMMdd HH:mm:ss.SSS",
            "yyyyMMdd HH:mm:ss",
            "yyyyMMdd",
        ).map { TimeFormat.Pattern(it) }

    /** The format for [samples], starting from [stated] (null to guess); throws [IllegalArgumentException] naming the problem. */
    fun resolve(
        stated: TimeFormat?,
        samples: List<String>,
        latestMs: Long,
    ): TimeFormat {
        require(samples.isNotEmpty()) { "no timestamps to read" }
        return when (stated) {
            is TimeFormat.Epoch -> epoch(stated, samples, latestMs)
            null -> guess(samples, latestMs)
            else -> stated.also { f -> samples.forEach { TimeText.parse(it, f) } }
        }
    }

    private fun guess(
        samples: List<String>,
        latestMs: Long,
    ): TimeFormat {
        val asEpoch =
            if (samples.all { TimeText.looksNumeric(it) }) {
                try {
                    epoch(TimeFormat.Epoch(null), samples, latestMs)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else {
                null
            }
        if (asEpoch != null) return asEpoch
        if (samples.all { parses(it, TimeFormat.Iso) }) return TimeFormat.Iso
        val fits = PATTERNS.filter { p -> samples.all { parses(it, p) } }
        return when (fits.size) {
            1 -> fits.single()
            0 -> throw IllegalArgumentException(
                "'${samples.first()}' matches no known time format; pass --time-format <pattern>",
            )
            else ->
                throw IllegalArgumentException(
                    "'${samples.first()}' fits several formats (${fits.joinToString { it.spec }}); pass --time-format",
                )
        }
    }

    private fun epoch(
        stated: TimeFormat.Epoch,
        samples: List<String>,
        latestMs: Long,
    ): TimeFormat.Epoch {
        val values =
            samples.map {
                DecimalText.parse(it) ?: throw IllegalArgumentException("empty epoch timestamp")
            }
        val unit =
            stated.unit ?: EpochUnit.detect(values)
                ?: throw IllegalArgumentException(
                    "epoch values ${values.first().toPlainString()}…${values.last().toPlainString()} are not all " +
                        "seconds, milliseconds, microseconds or nanoseconds; pass --time-format epoch:s|ms|us|ns",
                )
        val ms = values.map { unit.toMillis(it) }
        val bad = ms.firstOrNull { it < EpochUnit.EARLIEST_MS || it > latestMs }
        require(bad == null) {
            "epoch ${values[
                ms.indexOf(
                    bad,
                ),
            ].toPlainString()} read as ${unit.name.lowercase()} is outside 1990..now; check --time-format"
        }
        return TimeFormat.Epoch(unit)
    }

    private fun parses(
        text: String,
        format: TimeFormat,
    ): Boolean =
        try {
            TimeText.parse(text, format)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
