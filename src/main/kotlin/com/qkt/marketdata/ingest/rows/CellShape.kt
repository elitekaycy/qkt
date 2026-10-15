package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.parse.NumberStyle

/** What a cell looks like before its column's meaning is known; drives dialect, header and role detection. */
internal enum class CellShape {
    EMPTY,
    NUMBER,
    TIME,
    TEXT,
    ;

    /** True for shapes a market-data body row is made of. */
    val typed: Boolean get() = this != TEXT

    companion object {
        private val NUMBER_DOT = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")
        private val NUMBER_COMMA = Regex("""[+-]?\d+(,\d+)?([eE][+-]?\d+)?""")
        private val DATE = """(\d{4}[-./]\d{1,2}[-./]\d{1,2}|\d{1,2}[-./]\d{1,2}[-./]\d{2,4}|\d{8})"""
        private val CLOCK = """\d{1,2}:?\d{2}(:?\d{2})?([.,]\d{1,9})?|\d{6}\d{0,9}( \d{1,7})?"""
        private val ZONE = """(\s*(Z|[+-]\d{2}:?\d{2}|GMT[+-]\d{4}|UTC|[A-Z][a-z]+/[A-Za-z_]+))?"""
        private val TIME_TEXT =
            Regex("""$DATE([ T]($CLOCK))?$ZONE|\d{1,2}:\d{2}(:\d{2})?([.,]\d{1,9})?( ?[AaPp][Mm])?""")

        /** The shape of [raw] when numbers use [decimal] as their separator. */
        fun of(
            raw: String,
            decimal: Char = '.',
        ): CellShape {
            val t = raw.trim()
            return when {
                t.isEmpty() || t in NumberStyle.DEFAULT_NULLS -> EMPTY
                (if (decimal == ',') NUMBER_COMMA else NUMBER_DOT).matches(t) -> NUMBER
                TIME_TEXT.matches(t) -> TIME
                else -> TEXT
            }
        }
    }
}
