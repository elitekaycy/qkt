package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.InputLine
import com.qkt.marketdata.ingest.source.ImportInput
import com.qkt.marketdata.ingest.source.TextDecoding
import java.io.BufferedReader
import java.nio.charset.Charset

/** What a pass over one input skipped besides its data rows, for the report. */
data class RowSkips(
    var repeatedHeaders: Int = 0,
    var textRows: Int = 0,
    var blankLines: Int = 0,
)

/**
 * Streams the rows of one CSV-like input in a known [CsvDialect]: the preamble and header are skipped, a
 * header repeated later (files concatenated with `cat`) is skipped and counted, as are text-only rows
 * before the first data row (the second and third header lines of a pandas MultiIndex export). A row
 * with the wrong number of fields refuses: a short row is a broken row, never one to pad.
 */
internal object CsvRows {
    /** The first [count] non-blank lines of [input], for sniffing. */
    fun head(
        input: ImportInput,
        count: Int,
        charset: Charset?,
    ): List<String> =
        open(input, charset).use { reader ->
            generateSequence { reader.readLine() }
                .map(::clean)
                .filter { it.isNotBlank() }
                .take(count)
                .toList()
        }

    /** Calls [onRow] for every data row of [input]; returns what was skipped. */
    fun read(
        input: ImportInput,
        dialect: CsvDialect,
        charset: Charset?,
        onRow: (ImportRow) -> Unit,
    ): RowSkips {
        val skips = RowSkips()
        val header = dialect.header?.map { it.trim().lowercase() }
        var toSkip = dialect.preamble + if (dialect.header != null) 1 else 0
        var lineNo = 0L
        var seenData = false
        open(input, charset).use { reader ->
            while (true) {
                val raw = reader.readLine() ?: break
                lineNo++
                val line = clean(raw)
                if (line.isBlank()) {
                    skips.blankLines++
                    continue
                }
                if (toSkip > 0) {
                    toSkip--
                    continue
                }
                val text = if (dialect.wholeLineQuoted) unwrapWholeLine(line) else line
                val where = InputLine(input.name, lineNo, line)
                var cells = split(text, dialect.delimiter, where)
                if (dialect.dropTrailingEmpty && cells.size == dialect.columns + 1 && cells.last().isBlank()) {
                    cells = cells.dropLast(1)
                }
                if (header != null && cells.map { it.trim().lowercase() } == header) {
                    skips.repeatedHeaders++
                    continue
                }
                if (!seenData &&
                    cells.none {
                        CellShape.of(it, dialect.decimal).let { s ->
                            s == CellShape.NUMBER ||
                                s == CellShape.TIME
                        }
                    }
                ) {
                    skips.textRows++
                    continue
                }
                if (cells.size != dialect.columns) {
                    throw ImportRefusal(
                        "a row has ${cells.size} fields where the file has ${dialect.columns}",
                        listOf(where.toString()),
                    )
                }
                seenData = true
                onRow(CsvRow(where, cells))
            }
        }
        return skips
    }

    private fun split(
        text: String,
        delimiter: Char,
        where: InputLine,
    ): List<String> =
        try {
            splitCsvLine(text, delimiter)
        } catch (e: IllegalArgumentException) {
            throw ImportRefusal("a row has an ${e.message}", listOf(where.toString()))
        }

    private fun open(
        input: ImportInput,
        charset: Charset?,
    ): BufferedReader = TextDecoding.open(input.open(), charset)

    private fun clean(line: String): String = line.trimEnd('\u001a')
}
