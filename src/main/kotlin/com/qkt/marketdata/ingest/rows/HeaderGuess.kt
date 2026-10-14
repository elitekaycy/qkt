package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal

/**
 * Whether a file's first row is a header: it is when some column holds text in that row and typed values
 * (numbers, times) in at least nine body rows out of ten, or when the row uses MetaTrader's `<DATE>`-style
 * tags. A column that is text all the way down (a symbol, a trade side) says nothing either way, and body
 * rows with no number or time at all (the extra header lines of a pandas MultiIndex export) are left out.
 */
internal object HeaderGuess {
    private const val TYPED_SHARE = 0.9

    fun isHeader(
        first: List<String>,
        body: List<List<String>>,
        decimal: Char,
    ): Boolean {
        if (first.any { it.trim().startsWith('<') && it.trim().endsWith('>') }) return true
        val data =
            body.filter { row ->
                row.any {
                    CellShape.of(it, decimal).let { s ->
                        s == CellShape.NUMBER ||
                            s == CellShape.TIME
                    }
                }
            }
        if (data.isEmpty()) return first.all { CellShape.of(it, decimal) == CellShape.TEXT }
        return first.indices.any { i ->
            val shapes = data.mapNotNull { it.getOrNull(i) }.map { CellShape.of(it, decimal) }
            CellShape.of(first[i], decimal) == CellShape.TEXT &&
                shapes.isNotEmpty() &&
                shapes.count { it == CellShape.NUMBER || it == CellShape.TIME } >= TYPED_SHARE * shapes.size
        }
    }
}

/**
 * The decimal separator a non-comma-delimited file uses, from every number-like cell: `,` when cells read
 * like `1,10024`, `.` otherwise. `1,234` alone could be either (a thousands group or three decimals), so a
 * file whose comma numbers all have exactly three digits after the comma refuses, as does one that mixes
 * `,` and `.` decimals.
 */
internal object DecimalGuess {
    private val COMMA = Regex("""[+-]?\d+,\d+""")
    private val DOT = Regex("""[+-]?\d+\.\d+""")
    private const val GROUP_DIGITS = 3

    fun of(
        rows: List<List<String>>,
        delimiter: Char,
    ): Char {
        if (delimiter == ',') return '.'
        val cells = rows.flatten().map { it.trim() }
        val comma = cells.filter { COMMA.matches(it) }
        if (comma.isEmpty()) return '.'
        if (cells.any { DOT.matches(it) }) {
            throw ImportRefusal("numbers use both ',' and '.' as the decimal separator; pass --decimal")
        }
        if (comma.all { it.substringAfter(',').length == GROUP_DIGITS }) {
            throw ImportRefusal(
                "numbers like '${comma.first()}' could be a thousands group or a decimal comma; pass --decimal , or --decimal .",
            )
        }
        return ','
    }
}
