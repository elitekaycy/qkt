package com.qkt.cli.fetch

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The inclusive UTC day range to fetch: either (--from + --to) or (--last Nd), where `--last`
 * ends yesterday. Returns null on a parse error after printing it.
 */
internal fun resolveFetchRange(
    from: String?,
    to: String?,
    last: String?,
): Pair<LocalDate, LocalDate>? {
    if (last != null) {
        val days = parseLastDays(last) ?: return null
        val today = LocalDate.now(ZoneOffset.UTC)
        return today.minusDays(days.toLong()) to today.minusDays(1)
    }
    if (from == null || to == null) {
        System.err.println("qkt: need either --from + --to or --last <Nd>")
        return null
    }
    return try {
        LocalDate.parse(from) to LocalDate.parse(to)
    } catch (e: Exception) {
        System.err.println("qkt: invalid date in --from/--to: ${e.message}")
        null
    }
}

private fun parseLastDays(s: String): Int? {
    val m = Regex("^(\\d+)d$").matchEntire(s)
    if (m == null) {
        System.err.println("qkt: --last must be like '30d', got '$s'")
        return null
    }
    return m.groupValues[1].toInt()
}
