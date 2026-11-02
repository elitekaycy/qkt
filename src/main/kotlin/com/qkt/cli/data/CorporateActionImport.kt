package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.InstrumentFiles
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionStore
import com.qkt.instrument.QktSymbols
import com.qkt.marketdata.ingest.parse.DecimalText
import com.qkt.marketdata.ingest.rows.splitCsvLine
import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * `qkt data import <file>... --as <SYMBOL> --kind corporate-actions [--map field=column] [--action dividend|split]
 * [--currency CCY] [--time-format PATTERN] [--delimiter C] [--dry-run] [--json]`
 *
 * Reads a share's dividends and splits from any delimited file with a header and merges them into
 * `<data root>/corporate-actions/<VENUE>/<NAME>.csv` ([CorporateActionStore]). The file is described, not
 * recognised: `--map` names the columns (`ex_date`, `kind`, `value`, `currency`, `pay_date`; qkt's own file needs
 * none), `--action` stands in for a missing `kind` column when the file holds one kind, `--currency` for a missing
 * currency. A split's value is its ratio, as a number (`10`, `0.125`) or `new:old` (`10:1`, `1:8`). Every row is read
 * and checked before anything is written; a refused row names its line.
 */
internal object CorporateActionImport {
    const val KIND = "corporate-actions"
    private val FIELDS = setOf("ex_date", "kind", "value", "currency", "pay_date")

    fun run(
        args: Args,
        paths: List<Path>,
        dataRoot: Path,
    ): Int {
        val symbol = args.option("as")
        if (symbol == null || !QktSymbols.isFileSafe(symbol) || ':' !in symbol) {
            System.err.println("qkt: --as <VENUE:SYMBOL> is required for corporate actions, e.g. --as EXNESS:AAPL")
            return ExitCodes.ARG_ERROR
        }
        val actions =
            try {
                val columns = columns(args)
                val format =
                    args.option("time-format")?.let(DateTimeFormatter::ofPattern) ?: DateTimeFormatter.ISO_LOCAL_DATE
                val action = args.option("action")?.let(::kindOf)
                val delimiter = args.option("delimiter")?.singleOrNull() ?: ','
                paths.flatMap { read(it, columns, format, action, args.option("currency"), delimiter) }
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: ${e.message}\n  Nothing was written.")
                return ExitCodes.USER_ERROR
            }
        val quote =
            InstrumentFiles
                .registry(
                    dataRoot,
                    args.option("instruments")?.let(Path::of),
                ).lookup(symbol)
                ?.currency
        val foreign =
            actions.filterIsInstance<CorporateAction.Dividend>().firstOrNull {
                quote != null &&
                    it.currency != quote
            }
        if (foreign != null) {
            System.err.println(
                "qkt: $symbol is quoted in $quote; the dividend on ${foreign.exDate} is in ${foreign.currency}\n  Nothing was written.",
            )
            return ExitCodes.USER_ERROR
        }
        val store = CorporateActionStore(dataRoot)
        val stored =
            try {
                if (args.flag("dry-run")) null else store.merge(symbol, actions)
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: ${e.message}\n  Nothing was written.")
                return ExitCodes.USER_ERROR
            }
        val dividends = actions.count { it is CorporateAction.Dividend }
        val splits = actions.size - dividends
        if (args.flag("json")) {
            println(
                """{"symbol":"$symbol","read":${actions.size},"dividends":$dividends,"splits":$splits,""" +
                    """"stored":${stored ?: "null"},"path":${kotlinx.serialization.json.JsonPrimitive(
                        store.path(symbol).toString(),
                    )},"dryRun":${stored == null}}""",
            )
        } else {
            val verb = if (stored == null) "would merge into" else "merged into"
            println(
                "$symbol: $dividends dividend(s), $splits split(s) read; $verb ${store.path(symbol)}" +
                    (stored?.let { ", which holds $it" } ?: ""),
            )
        }
        return ExitCodes.SUCCESS
    }

    /** The column each field is read from: qkt's own names unless `--map` says otherwise. */
    private fun columns(args: Args): Map<String, String> {
        val mapped =
            args.options("map").flatMap { it.split(',') }.filter { it.isNotBlank() }.associate {
                val (field, column) =
                    it.split('=', limit = 2).map(String::trim).takeIf { p -> p.size == 2 }
                        ?: throw IllegalArgumentException("--map takes field=column: $it")
                require(field in FIELDS) { "--map field '$field' is not one of ${FIELDS.joinToString()}" }
                field to column
            }
        return FIELDS.associateWith { mapped[it] ?: it }
    }

    private fun read(
        path: Path,
        columns: Map<String, String>,
        format: DateTimeFormatter,
        action: Kind?,
        currency: String?,
        delimiter: Char,
    ): List<CorporateAction> {
        val lines = Files.readAllLines(path).filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "$path is empty" }
        val header = splitCsvLine(lines.first().removePrefix(""), delimiter).map(String::trim)

        fun index(field: String): Int? =
            header.indexOfFirst { it.equals(columns.getValue(field), ignoreCase = true) }.takeIf {
                it >=
                    0
            }
        val exDate =
            index("ex_date") ?: throw IllegalArgumentException("$path has no '${columns.getValue("ex_date")}' column")
        val value =
            index("value") ?: throw IllegalArgumentException("$path has no '${columns.getValue("value")}' column")
        val kind = index("kind")
        require(kind != null || action != null) {
            "$path has no '${columns.getValue("kind")}' column; pass --action dividend|split"
        }
        val ccy = index("currency")
        val pay = index("pay_date")
        return lines.drop(1).mapIndexed { i, line ->
            runCatching {
                val cells = splitCsvLine(line, delimiter).map(String::trim)

                fun cell(at: Int?) = at?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() }
                val date = LocalDate.parse(cell(exDate) ?: error("no ex-date"), format)
                val raw = cell(value) ?: error("no value")
                when (cell(kind)?.let(::kindOf) ?: action!!) {
                    Kind.DIVIDEND ->
                        CorporateAction.Dividend(
                            date,
                            DecimalText.parse(raw) ?: error("no amount"),
                            cell(ccy) ?: currency ?: error("no currency; pass --currency"),
                            cell(pay)?.let { LocalDate.parse(it, format) },
                        )
                    Kind.SPLIT -> CorporateAction.Split(date, ratio(raw))
                }
            }.getOrElse { throw IllegalArgumentException("$path line ${i + 2}: ${it.message}") }
        }
    }

    private enum class Kind { DIVIDEND, SPLIT }

    private fun kindOf(text: String): Kind =
        when (text.trim().lowercase()) {
            "dividend", "dividends" -> Kind.DIVIDEND
            "split", "splits" -> Kind.SPLIT
            else -> throw IllegalArgumentException("'$text' is neither a dividend nor a split")
        }

    /** A ratio as `10`, `0.125` or `new:old` (`10:1`, `1:8`). */
    private fun ratio(text: String): BigDecimal {
        val parts = text.split(':', '/')
        if (parts.size == 1) return DecimalText.parse(text) ?: error("no ratio")
        require(parts.size == 2) { "split ratio '$text' is not new:old" }
        val (new, old) = parts.map { DecimalText.parse(it) ?: error("no ratio") }
        require(old.signum() > 0) { "split ratio '$text' has a zero old count" }
        return new.divide(old, MathContext.DECIMAL64).stripTrailingZeros()
    }
}
