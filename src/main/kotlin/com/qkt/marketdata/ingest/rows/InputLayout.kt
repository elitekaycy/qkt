package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.source.ImportInput
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/**
 * How one input is laid out, found once and then used to stream its rows: CSV in a sniffed [CsvDialect],
 * or JSON rows at a path. [names] are the column (or object key) names a mapping can use; [columns] is
 * the row width for CSV and JSON array rows.
 */
sealed interface InputLayout {
    val names: List<String>?
    val columns: Int
    val firstCell: String?

    /** Each column's ticker, when the file names one per column under its header (yfinance, several tickers). */
    val tickers: List<String>? get() = null

    /** Streams every row of [input]; returns what was skipped (CSV only). */
    fun read(
        input: ImportInput,
        onRow: (ImportRow) -> Unit,
    ): RowSkips

    data class Csv(
        val dialect: CsvDialect,
        val charset: Charset?,
        override val firstCell: String?,
    ) : InputLayout {
        override val names: List<String>? get() = dialect.header
        override val columns: Int get() = dialect.columns
        override val tickers: List<String>? get() = dialect.tickers

        override fun read(
            input: ImportInput,
            onRow: (ImportRow) -> Unit,
        ): RowSkips = decoding(input) { CsvRows.read(input, dialect, charset, onRow) }
    }

    data class Json(
        val rowsPath: List<String>,
        val charset: Charset?,
        override val names: List<String>?,
        override val columns: Int,
        override val firstCell: String?,
    ) : InputLayout {
        override fun read(
            input: ImportInput,
            onRow: (ImportRow) -> Unit,
        ): RowSkips {
            decoding(input) { JsonRows.read(input, rowsPath, charset, onRow) }
            return RowSkips()
        }
    }

    companion object {
        private const val SNIFF_LINES = 2000

        /** The layout of [input]; [json], [delimiter] and [decimal] fix what would otherwise be detected. */
        fun of(
            input: ImportInput,
            json: Boolean?,
            rowsPath: List<String>,
            delimiter: Char?,
            decimal: Char?,
            charset: Charset?,
        ): InputLayout {
            val head = decoding(input) { CsvRows.head(input, SNIFF_LINES, charset) }
            if (head.isEmpty()) throw ImportRefusal("${input.name} is empty")
            if (json ?: JsonRows.isJson(head.first())) {
                var first: ImportRow? = null
                decoding(input) { JsonRows.read(input, rowsPath, charset) { if (first == null) first = it } }
                val row = first as? JsonRow ?: throw ImportRefusal("${input.name} holds no JSON rows")
                return Json(rowsPath, charset, row.keys(), row.width(), row.cell(CellRef.Index(0)) ?: row.firstValue())
            }
            val dialect = CsvDialectSniffer.sniff(head, delimiter, decimal)
            val body = head.drop(dialect.preamble + if (dialect.header != null) 1 else 0)
            val firstData =
                body
                    .map { if (dialect.wholeLineQuoted) unwrapWholeLine(it) else it }
                    .map { splitCsvLine(it, dialect.delimiter) }
                    .firstOrNull { cells ->
                        cells.any {
                            CellShape.of(it, dialect.decimal).let { s ->
                                s ==
                                    CellShape.NUMBER ||
                                    s == CellShape.TIME
                            }
                        }
                    }
            return Csv(dialect, charset, firstData?.firstOrNull())
        }

        private fun <T> decoding(
            input: ImportInput,
            block: () -> T,
        ): T =
            try {
                block()
            } catch (e: CharacterCodingException) {
                throw ImportRefusal(
                    "${input.name} is not valid UTF-8 or UTF-16 text (${e.message}); pass --encoding latin-1 if it is Latin-1",
                )
            }
    }
}
