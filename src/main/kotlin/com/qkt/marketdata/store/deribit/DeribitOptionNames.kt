package com.qkt.marketdata.store.deribit

import com.qkt.instrument.OptionContract
import com.qkt.instrument.OptionRight
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Deribit option names: `<UNDERLYING>-<DMMMYY>-<STRIKE>-<C|P>`, e.g. `BTC_USDC-27SEP24-60000-C`,
 * with a decimal strike written with `d` (`9d5` is 9.5). Deribit options expire at 08:00 UTC on
 * their date.
 */
object DeribitOptionNames {
    private val pattern = Regex("""^([A-Z0-9]+_[A-Z0-9]+)-(\d{1,2})([A-Z]{3})(\d{2})-(\d+(?:d\d+)?)-([CP])$""")
    private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val expiryTime: LocalTime = LocalTime.of(8, 0)

    /** The contract [name] denotes, or null when it is not a well-formed option name. */
    fun parse(name: String): OptionContract? {
        val match = pattern.matchEntire(name) ?: return null
        val (_, day, month, year, strikeText, right) = match.destructured
        val monthNumber = months.indexOf(month) + 1
        if (monthNumber == 0) return null
        val date =
            runCatching { LocalDate.of(2000 + year.toInt(), monthNumber, day.toInt()) }.getOrNull() ?: return null
        val strike = BigDecimal(strikeText.replace('d', '.'))
        if (strike.signum() <= 0) return null
        val expiry = date.atTime(expiryTime).toInstant(ZoneOffset.UTC).toEpochMilli()
        return OptionContract(name, strike, if (right == "C") OptionRight.CALL else OptionRight.PUT, expiry)
    }

    /** The underlying part of [name] (`BTC_USDC`), or null when [name] is not an option name. */
    fun underlyingOf(name: String): String? = pattern.matchEntire(name)?.groupValues?.get(1)
}
