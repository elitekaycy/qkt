package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal

/**
 * How one CSV-like file is laid out. [preamble] lines come before the header (or the first data row)
 * and are skipped, e.g. a vendor's URL banner. [header] is the header's cells, or null when the file
 * starts with data. [wholeLineQuoted] files quote every row as one field; [dropTrailingEmpty] files
 * end every row with a delimiter.
 */
data class CsvDialect(
    val delimiter: Char,
    val decimal: Char,
    val preamble: Int,
    val header: List<String>?,
    val columns: Int,
    val wholeLineQuoted: Boolean = false,
    val dropTrailingEmpty: Boolean = false,
)

/**
 * Detects a [CsvDialect] from a file's first lines. Each candidate delimiter is scored on row consistency
 * (the share of lines with the most common field count) weighted by typeability (the share of body cells
 * that are numbers, times or empty), the scheme CleverCSV showed beats frequency counting: typeability is
 * what tells `1,2;3,4` (semicolon, decimal comma) from a comma file, while text columns such as a symbol or
 * a trade side only lower it by half their weight. The winner must be consistent and clearly ahead: an
 * inconsistent best or a near tie refuses and names `--delimiter`.
 */
internal object CsvDialectSniffer {
    private val DELIMITERS = listOf(',', ';', '\t', '|')
    private const val MIN_CONSISTENCY = 0.9
    private const val TIE_MARGIN = 0.05

    /** The dialect of [lines] (the file's first lines, blank ones removed); [forced] fixes the delimiter. */
    fun sniff(
        lines: List<String>,
        forced: Char? = null,
        forcedDecimal: Char? = null,
    ): CsvDialect {
        if (lines.isEmpty()) throw ImportRefusal("the file is empty")
        if (lines.first().trimStart().startsWith("<") && !lines.first().trimStart().startsWith("<DATE>", true)) {
            throw ImportRefusal("the file is an HTML/XML page, not data (a login or download page saved as CSV?)")
        }
        val quoted = lines.all { unwrapWholeLine(it) != it }
        val body = if (quoted) lines.map(::unwrapWholeLine) else lines
        val candidates = (forced?.let { listOf(it) } ?: DELIMITERS).map { score(body, it, forcedDecimal) }
        val ranked = candidates.filter { it.columns >= 2 }.sortedByDescending { it.score }
        val best =
            ranked.firstOrNull()
                ?: throw ImportRefusal("no delimiter splits the file into columns; pass --delimiter")
        val runnerUp = ranked.getOrNull(1)
        if (forced == null &&
            (best.consistency < MIN_CONSISTENCY || (runnerUp != null && best.score - runnerUp.score < TIE_MARGIN))
        ) {
            throw ImportRefusal(
                "cannot tell the delimiter: " +
                    ranked.take(3).joinToString { "'${printable(it.delimiter)}' scores ${"%.2f".format(it.score)}" } +
                    "; pass --delimiter",
            )
        }
        return best.dialect.copy(wholeLineQuoted = quoted)
    }

    private class Candidate(
        val delimiter: Char,
        val columns: Int,
        val consistency: Double,
        val score: Double,
        val dialect: CsvDialect,
    )

    private fun score(
        lines: List<String>,
        delimiter: Char,
        forcedDecimal: Char?,
    ): Candidate {
        val split = lines.map { line -> runCatchingSplit(line, delimiter) }
        val counts = split.map { it?.size ?: -1 }
        val modal =
            counts
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: 0
        val preamble = counts.indexOfFirst { it == modal }.coerceAtLeast(0)
        val rows = split.drop(preamble).filterNotNull().filter { it.size == modal }
        val consistency = rows.size.toDouble() / (lines.size - preamble).coerceAtLeast(1)
        val decimal = forcedDecimal ?: DecimalGuess.of(rows.drop(1), delimiter)
        val headerCells = rows.firstOrNull()?.takeIf { HeaderGuess.isHeader(it, rows.drop(1), decimal) }
        val bodyRows = if (headerCells != null) rows.drop(1) else rows
        val cells = bodyRows.flatten()
        val typed =
            if (cells.isEmpty()) {
                0.0
            } else {
                cells.count { CellShape.of(it, decimal).typed }.toDouble() /
                    cells.size
            }
        val trailing = modal > 1 && rows.all { it.last().isBlank() }
        val columns = if (trailing) modal - 1 else modal
        val header = headerCells?.let { if (trailing) it.dropLast(1) else it }?.map { it.trim() }
        val dialect = CsvDialect(delimiter, decimal, preamble, header, columns, dropTrailingEmpty = trailing)
        val usable = if (modal < 2) modal else columns.coerceAtLeast(2)
        return Candidate(delimiter, usable, consistency, consistency * (0.5 + typed / 2), dialect)
    }

    private fun runCatchingSplit(
        line: String,
        delimiter: Char,
    ): List<String>? =
        try {
            splitCsvLine(line, delimiter)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun printable(c: Char): String = if (c == '\t') "\\t" else c.toString()
}
