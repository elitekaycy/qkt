package com.qkt.marketdata.ingest.parse

import java.math.BigDecimal

/**
 * How a source writes numbers: its decimal separator and the cell texts that mean "no value". A null
 * token is absent, never zero: an empty volume says nothing about whether anything traded.
 */
data class NumberStyle(
    val decimal: Char = '.',
    val nulls: Set<String> = DEFAULT_NULLS,
) {
    init {
        require(decimal == '.' || decimal == ',') { "decimal separator must be '.' or ',': '$decimal'" }
    }

    companion object {
        val DEFAULT_NULLS: Set<String> = setOf("", "null", "NULL", "NaN", "nan", "N/A", "n/a", "-", "None")
    }
}

/**
 * Exact decimal parsing of market-data cells. Text goes straight to [BigDecimal], never through a
 * double, so `981.6912000000001` stays exactly that and `3.146e-05` is exactly 0.00003146. Thousands
 * separators are not accepted: `1,234` is either one thousand two hundred and thirty-four or one point
 * two three four, and only the source can say which.
 */
object DecimalText {
    private val PLAIN = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    /** The exact value of [raw], or null for a null token; a non-number throws [NumberFormatException]. */
    fun parse(
        raw: String,
        style: NumberStyle = NumberStyle(),
    ): BigDecimal? {
        val text = raw.trim()
        if (text in style.nulls) return null
        val normalised =
            if (style.decimal == ',') {
                if ('.' in text) throw NumberFormatException("'$raw' has a '.' but the decimal separator is ','")
                text.replace(',', '.')
            } else {
                text
            }
        if (!PLAIN.matches(normalised)) throw NumberFormatException("not a number: '$raw'")
        return BigDecimal(normalised)
    }

    /** True when [raw] parses as a number (or a null token) under [style]. */
    fun isNumber(
        raw: String,
        style: NumberStyle = NumberStyle(),
    ): Boolean =
        try {
            parse(raw, style)
            true
        } catch (_: NumberFormatException) {
            false
        }
}
