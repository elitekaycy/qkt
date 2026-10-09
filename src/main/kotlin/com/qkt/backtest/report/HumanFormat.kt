package com.qkt.backtest.report

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Human-readable value formatting shared by the console, HTML and chart views (#1370, #1378,
 * #1379): money with separators and currency, fractions as percentages, epoch-ms as UTC dates,
 * durations as days/hours. Raw values stay in the CSV/JSON artifacts for tools; the HTML views
 * repeat them on hover via `title=`.
 */
internal object HumanFormat {
    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    /** Money, e.g. "+4,423.25 USD" or "-12.50"; "0.00" for zero. A blank currency omits the code. */
    fun money(
        amount: BigDecimal,
        currency: String?,
        signed: Boolean = true,
    ): String {
        val body = String.format(Locale.US, "%,.2f", amount.abs())
        val signedBody =
            when {
                !signed -> body
                amount.signum() > 0 -> "+$body"
                amount.signum() < 0 -> "-$body"
                else -> body
            }
        return if (currency.isNullOrBlank()) signedBody else "$signedBody ${currency.trim().uppercase()}"
    }

    /** Fraction as a percentage, e.g. 0.43244333 -> "43.2%"; signed adds "+" (returns: "(+0.5%)"). */
    fun percent(
        fraction: BigDecimal,
        decimals: Int = 1,
        signed: Boolean = false,
    ): String {
        val scaled = fraction.multiply(BigDecimal(100)).setScale(decimals, RoundingMode.HALF_EVEN)
        val sign = if (signed && fraction.signum() > 0) "+" else ""
        return "$sign$scaled%"
    }

    /** Ratio to two decimals, e.g. 1.2031 -> "1.20"; null -> "n/a". */
    fun ratio(value: BigDecimal?): String = value?.setScale(2, RoundingMode.HALF_EVEN)?.toPlainString() ?: "n/a"

    /** Epoch-ms as a UTC date, e.g. 1727686800000 -> "2024-09-30 09:00 UTC". */
    fun utcDate(epochMs: Long): String = dateFmt.format(Instant.ofEpochMilli(epochMs))

    /** Duration as days/hours/minutes, e.g. 16613100000 -> "192 days". */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            days >= 1L -> "$days ${if (days == 1L) "day" else "days"}"
            hours >= 1L -> "$hours ${if (hours == 1L) "hour" else "hours"}"
            else -> "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        }
    }

    /** Bare grouped number for chart axes, e.g. 15110.004 -> "15,110". */
    fun axisMoney(amount: BigDecimal): String = String.format(Locale.US, "%,.0f", amount)
}
