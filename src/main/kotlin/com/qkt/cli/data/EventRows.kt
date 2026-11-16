package com.qkt.cli.data

import com.qkt.instrument.CatalogFee
import com.qkt.instrument.CatalogStrike
import com.qkt.instrument.ListedEvent
import com.qkt.marketdata.ingest.parse.DecimalText
import com.qkt.marketdata.ingest.rows.splitCsvLine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Reads event contracts from a delimited file with a header, one contract a row, into catalog rows. The file is
 * described, not recognised: [columns] names the column each field is read from. Times are UTC epoch milliseconds or
 * ISO-8601 instants (`2026-11-06T20:00:00Z`); flags are `true`/`false` (also `yes`/`no`, `1`/`0`); an outcome is a
 * number in [0, 1] or `yes` (1) / `no` (0); an empty cell is an absent value. A row that cannot be read fails naming
 * its line.
 */
internal class EventRows(
    private val columns: Map<String, String>,
    private val delimiter: Char,
) {
    /** The contracts [path] holds, in file order. */
    fun read(path: Path): List<ListedEvent> {
        val lines = Files.readAllLines(path).filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "$path is empty" }
        val header = splitCsvLine(lines.first().removePrefix("﻿"), delimiter).map(String::trim)
        val at =
            FIELDS.associateWith { field ->
                header.indexOfFirst { it.equals(columns.getValue(field), ignoreCase = true) }.takeIf { it >= 0 }
            }
        for (field in REQUIRED) {
            require(at[field] != null) { "$path has no '${columns.getValue(field)}' column (field $field)" }
        }
        return lines.drop(1).mapIndexed { i, line ->
            val cells = splitCsvLine(line, delimiter).map(String::trim)
            val cell = { field: String -> at[field]?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() } }
            try {
                row(cell)
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("$path line ${i + 2}: ${e.message}", e)
            }
        }
    }

    private fun row(cell: (String) -> String?): ListedEvent {
        fun need(field: String) = cell(field) ?: error("no $field")
        val taker = cell("taker_fee")
        return ListedEvent(
            code = need("code"),
            event = need("event"),
            predicate = CatalogStrike(need("strike_type").lowercase(), number(cell("floor")), number(cell("cap"))),
            openAtMs = time(need("open")),
            closeAtMs = time(need("close")),
            expiryMs = time(need("expiry")),
            occursAtMs = cell("occurs")?.let(::time),
            occasion = cell("occasion"),
            canCloseEarly = cell("can_close_early")?.let(::flag) ?: false,
            exclusive = cell("exclusive")?.let(::flag) ?: false,
            frequency = cell("frequency"),
            title = cell("title"),
            fee =
                taker?.let {
                    CatalogFee(decimal(it), number(cell("maker_fee")), decimal(need("fee_rounding")))
                },
            outcome = cell("outcome")?.let(::outcome),
        )
    }

    private fun number(text: String?): String? = text?.let(::decimal)

    private fun decimal(text: String): String =
        (DecimalText.parse(text) ?: error("'$text' is not a number")).toPlainString()

    private fun time(text: String): Long =
        text.toLongOrNull() ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrElse {
            error("'$text' is neither epoch milliseconds nor an ISO-8601 instant")
        }

    private fun flag(text: String): Boolean =
        when (text.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> error("'$text' is not true or false")
        }

    private fun outcome(text: String): String =
        when (text.lowercase()) {
            "yes" -> "1"
            "no" -> "0"
            else -> decimal(text)
        }

    companion object {
        /** Every field a row may carry, by qkt's own column name. */
        val FIELDS =
            listOf(
                "code",
                "event",
                "strike_type",
                "floor",
                "cap",
                "open",
                "close",
                "expiry",
                "occurs",
                "occasion",
                "can_close_early",
                "exclusive",
                "frequency",
                "title",
                "taker_fee",
                "maker_fee",
                "fee_rounding",
                "outcome",
            )
        private val REQUIRED = listOf("code", "event", "strike_type", "open", "close", "expiry")
    }
}
