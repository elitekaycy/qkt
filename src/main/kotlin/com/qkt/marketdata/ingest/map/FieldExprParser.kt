package com.qkt.marketdata.ingest.map

import com.qkt.marketdata.ingest.rows.CellRef
import java.math.BigDecimal

/**
 * Parses a field mapping such as `<CLOSE>`, `$4`, `[4]`, `mid.o`, `'Gmt time'` or
 * `close + spread * point / 2`. A whole text that is a column name is that column, so header names with
 * spaces need no quoting; otherwise names are `<TAG>`, quoted text, `$N` / `[N]` positions, or plain names
 * (letters, digits, `_`, `.`, `@`). [resolve] turns a name into its cell, or null when there is no such
 * column, which is an error naming the columns there are.
 */
class FieldExprParser(
    private val resolve: (String) -> CellRef?,
    private val known: () -> List<String> = { emptyList() },
) {
    /** [text] as an expression. */
    fun parse(text: String): FieldExpr {
        resolve(text.trim())?.let { return FieldExpr.Cell(it) }
        val p = Cursor(text)
        val expr = sum(p)
        p.skipSpace()
        require(p.done) { "unexpected '${text.substring(p.at)}' in mapping '$text'" }
        return expr
    }

    private class Cursor(
        val text: String,
    ) {
        var at = 0
        val done get() = at >= text.length

        fun skipSpace() {
            while (!done && text[at].isWhitespace()) at++
        }

        fun peek(): Char? {
            skipSpace()
            return text.getOrNull(at)
        }
    }

    private fun sum(p: Cursor): FieldExpr {
        var left = product(p)
        while (p.peek() == '+' || p.peek() == '-') {
            val op = p.text[p.at++]
            left = FieldExpr.Binary(op, left, product(p))
        }
        return left
    }

    private fun product(p: Cursor): FieldExpr {
        var left = unary(p)
        while (p.peek() == '*' || p.peek() == '/') {
            val op = p.text[p.at++]
            left = FieldExpr.Binary(op, left, unary(p))
        }
        return left
    }

    private fun unary(p: Cursor): FieldExpr {
        val c = p.peek() ?: throw IllegalArgumentException("mapping '${p.text}' ends early")
        return when {
            c == '-' -> {
                p.at++
                FieldExpr.Binary('-', FieldExpr.Constant(BigDecimal.ZERO), unary(p))
            }
            c == '(' -> {
                p.at++
                val inner = sum(p)
                require(p.peek() == ')') { "missing ')' in mapping '${p.text}'" }
                p.at++
                inner
            }
            c.isDigit() || c == '.' ->
                FieldExpr.Constant(
                    BigDecimal(
                        take(p) {
                            it.isDigit() ||
                                it == '.' ||
                                it == 'e' ||
                                it == 'E'
                        },
                    ),
                )
            else -> name(p)
        }
    }

    private fun name(p: Cursor): FieldExpr {
        val c = p.text[p.at]
        val token =
            when (c) {
                '<' -> take(p, inclusiveEnd = '>')
                '\'', '"' -> {
                    p.at++
                    take(p) { it != c }.also { p.at++ }
                }
                '$' -> {
                    p.at++
                    return FieldExpr.Cell(CellRef.Index(take(p) { it.isDigit() }.toInt()))
                }
                '[' -> {
                    p.at++
                    val index = take(p) { it.isDigit() }.toInt()
                    require(p.peek() == ']') { "missing ']' in mapping '${p.text}'" }
                    p.at++
                    return FieldExpr.Cell(CellRef.Index(index))
                }
                else -> take(p) { it.isLetterOrDigit() || it == '_' || it == '.' || it == '@' }
            }
        require(token.isNotEmpty()) { "unexpected '$c' in mapping '${p.text}'" }
        if (token == "point" || token == "digits") return FieldExpr.Symbol(token)
        val ref =
            resolve(token)
                ?: throw IllegalArgumentException(
                    "no column '$token'" +
                        known().takeIf { it.isNotEmpty() }?.let { " (columns: ${it.joinToString()})" }.orEmpty(),
                )
        return FieldExpr.Cell(ref)
    }

    private fun take(
        p: Cursor,
        inclusiveEnd: Char? = null,
        keep: (Char) -> Boolean = { true },
    ): String {
        val start = p.at
        if (inclusiveEnd != null) {
            val end = p.text.indexOf(inclusiveEnd, start)
            require(end > start) { "missing '$inclusiveEnd' in mapping '${p.text}'" }
            p.at = end + 1
            return p.text.substring(start, end + 1)
        }
        while (!p.done && keep(p.text[p.at])) p.at++
        return p.text.substring(start, p.at)
    }
}
