package com.qkt.marketdata.ingest.parse

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor

/** A parsed timestamp before any clock is applied. */
sealed interface ParsedTime {
    /** An instant the text pinned down itself (epoch, `Z`, an offset). */
    data class Instant(
        val epochMs: Long,
        val subMillis: Boolean = false,
    ) : ParsedTime

    /** A wall-clock time with no zone: it needs the source's clock. */
    data class Local(
        val value: LocalDateTime,
        val subMillis: Boolean = false,
    ) : ParsedTime

    /** A calendar date only (daily bars): it names a session day, not an instant. */
    data class Date(
        val value: LocalDate,
    ) : ParsedTime
}

/** Parses one timestamp cell in a known [TimeFormat]; any text the format does not fit is an [IllegalArgumentException]. */
object TimeText {
    /** [raw] read as [format]; an `epoch` format must already carry its unit (see [EpochUnit.detect]). */
    fun parse(
        raw: String,
        format: TimeFormat,
    ): ParsedTime {
        val text = raw.trim()
        return try {
            when (format) {
                is TimeFormat.Epoch -> epoch(text, format.unit ?: error("epoch unit not resolved for '$text'"))
                TimeFormat.Iso -> iso(text)
                is TimeFormat.Pattern -> resolve(format.formatter.parse(text))
            }
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("'$text' does not match time format '${format.spec}'", e)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("'$text' is not an epoch number", e)
        }
    }

    private fun epoch(
        text: String,
        unit: EpochUnit,
    ): ParsedTime.Instant {
        val value =
            DecimalText.parse(text) ?: throw IllegalArgumentException("empty timestamp")
        if (value.signum() < 0) throw IllegalArgumentException("negative epoch '$text'")
        return ParsedTime.Instant(unit.toMillis(value), unit.hasSubMillis(value))
    }

    private fun iso(text: String): ParsedTime {
        if (text.length == DATE_LENGTH) return ParsedTime.Date(LocalDate.parse(text))
        val normalised =
            if (text.length > DATE_LENGTH &&
                text[DATE_LENGTH] == ' '
            ) {
                text.replaceRange(10, 11, "T")
            } else {
                text
            }
        val parsed = DateTimeFormatter.ISO_DATE_TIME.parse(normalised)
        return resolve(parsed)
    }

    private fun resolve(parsed: TemporalAccessor): ParsedTime {
        val date = LocalDate.from(parsed)
        if (!parsed.isSupported(ChronoField.HOUR_OF_DAY)) return ParsedTime.Date(date)
        val time = LocalTime.from(parsed)
        val sub = time.nano % NANOS_PER_MILLI != 0
        val local = LocalDateTime.of(date, time)
        if (!parsed.isSupported(ChronoField.OFFSET_SECONDS)) return ParsedTime.Local(local, sub)
        val offset = ZoneOffset.ofTotalSeconds(parsed.get(ChronoField.OFFSET_SECONDS))
        return ParsedTime.Instant(OffsetDateTime.of(local, offset).toInstant().toEpochMilli(), sub)
    }

    /** True when [raw] is a plain non-negative number, the only text an epoch column holds. */
    fun looksNumeric(raw: String): Boolean = EPOCH_TEXT.matches(raw.trim())

    private val EPOCH_TEXT = Regex("""\d+(\.\d+)?""")
    private const val DATE_LENGTH = 10
    private const val NANOS_PER_MILLI = 1_000_000
}
