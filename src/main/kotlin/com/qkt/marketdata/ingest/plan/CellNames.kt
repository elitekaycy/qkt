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
) {
    private val byName =
        layout.names
            ?.withIndex()
            ?.associate { (i, n) -> n.trim().lowercase() to i }
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
        return byName[n.lowercase()]?.let { CellRef.Index(it) }
    }

    /** [text] compiled; a trailing `?` makes a missing column an absent value instead of an error. */
    fun expr(text: String): FieldExpr {
        val optional = text.trimEnd().endsWith('?')
        val body = if (optional) text.trimEnd().dropLast(1) else text
        val parser = FieldExprParser(::resolve) { layout.names.orEmpty() }
        return try {
            parser.parse(body)
        } catch (e: IllegalArgumentException) {
            if (optional &&
                body.trim().let { resolve(it) == null && it.none { c -> c in "+-*/()" } }
            ) {
                FieldExpr.Missing
            } else {
                throw e
            }
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
