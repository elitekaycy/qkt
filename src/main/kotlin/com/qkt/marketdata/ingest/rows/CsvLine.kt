package com.qkt.marketdata.ingest.rows

/**
 * Splits one CSV line on [delimiter], honouring double-quoted fields (`"a,b"`) and doubled quotes inside
 * them (`"say ""hi"""`). An unterminated quote throws: the line is broken, not a long field.
 */
internal fun splitCsvLine(
    line: String,
    delimiter: Char,
): List<String> {
    if ('"' !in line) return line.split(delimiter)
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                cell.append('"')
                i++
            }
            c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
            !quoted && c == delimiter -> {
                cells += cell.toString()
                cell.setLength(0)
            }
            else -> cell.append(c)
        }
        i++
    }
    require(!quoted) { "unterminated quote" }
    cells += cell.toString()
    return cells
}

/** [line] without the quotes of a whole-line-quoted export (`"2008-01-02,1467.96,…"`), as some tools write. */
internal fun unwrapWholeLine(line: String): String =
    if (line.length >= 2 && line.first() == '"' && line.last() == '"' && line.count { it == '"' } == 2) {
        line.substring(1, line.length - 1)
    } else {
        line
    }
