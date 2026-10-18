package com.qkt.marketdata.ingest.plan

import com.qkt.marketdata.ingest.map.FieldExpr
import com.qkt.marketdata.ingest.map.FieldExprParser
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.InputLayout
import com.qkt.marketdata.ingest.rows.JsonRow

/**
 * Resolves the names a mapping uses against one input's layout: a CSV column by header name (case and
 * surrounding space ignored), a JSON object key or dotted path (`mid.o`), `@key` for the key an object
 * of rows lists a row under, and `$N` / `[N]` positions everywhere.
 */
class CellNames(
    private val layout: InputLayout,
    /** The symbol imported, which picks a ticker's column when several tickers share a column name. */
    private val ticker: String? = null,
) {
    private val byName: Map<String, List<Int>> =
        layout.names
            ?.withIndex()
            ?.groupBy({ (_, n) -> n.trim().lowercase() }, { (i, _) -> i })
            .orEmpty()

    /** The cell [name] refers to, or null when the input has no such column. */
    fun resolve(name: String): CellRef? {
        val n = name.trim()
        POSITION.matchEntire(n)?.let { return CellRef.Index(it.groupValues[1].toInt()) }
        if (layout is InputLayout.Json) {
            if (n == JsonRow.KEY) return CellRef.Path(listOf(JsonRow.KEY))
            if (layout.names == null) return null
            val head = n.substringBefore('.')
            return if (layout.names.any { it == head }) CellRef.Path(n.split('.')) else null
        }
        val at = byName[n.lowercase()] ?: return null
        return CellRef.Index(at.singleOrNull() ?: oneOf(n, at))
    }

    /**
     * The one of several same-named columns that belongs to [ticker], or a refusal: taking any of them would
     * read another instrument's prices without a word.
     */
    private fun oneOf(
        name: String,
        at: List<Int>,
    ): Int {
        val tickers = layout.tickers
        if (tickers != null) {
            val named = at.map { tickers.getOrNull(it).orEmpty() }
            val mine = at.filter { tickers.getOrNull(it).equals(ticker, ignoreCase = true) }
            mine.singleOrNull()?.let { return it }
            throw IllegalArgumentException(
                "the file holds several tickers (${named.distinct().joinToString()}) and none is " +
                    "'${ticker.orEmpty()}': import with --as <SOURCE>:<ticker>, one ticker at a time",
            )
        }
        throw IllegalArgumentException(
            "column '$name' appears ${at.size} times in the header (positions ${at.joinToString { "\$$it" }}): " +
                "say which with --map, e.g. --map ${name.lowercase()}=\$${at.first()}",
        )
    }

    /**
     * [text] compiled. A trailing `?` makes the field optional: when a column it names is not in the file
     * (`Volume?`, `bidVolume + askVolume?`), the field is absent instead of an error.
     */
    fun expr(text: String): FieldExpr {
        val optional = text.trimEnd().endsWith('?')
        val body = if (optional) text.trimEnd().dropLast(1) else text
        val parser = FieldExprParser(::resolve) { layout.names.orEmpty() }
        return try {
            parser.parse(body)
        } catch (e: IllegalArgumentException) {
            if (optional && e.message.orEmpty().startsWith("no column")) FieldExpr.Missing else throw e
        }
    }

    /** The cell for a time or filter reference, failing with the columns there are when there is none. */
    fun cell(name: String): CellRef =
        resolve(name)
            ?: throw IllegalArgumentException(
                "no column '$name'" +
                    (
                        layout.names?.let { " (columns: ${it.joinToString()})" }
                            ?: " (the file has no header: use \$0, \$1, …)"
                    ),
            )

    private companion object {
        val POSITION = Regex("""[$\[](\d+)]?""")
    }
}
