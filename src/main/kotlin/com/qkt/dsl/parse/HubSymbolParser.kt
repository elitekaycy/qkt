package com.qkt.dsl.parse

/**
 * Parses the name in a `HUB:` stream declaration and holds it to the hub store's naming rules,
 * so a stream that could never match a record fails at the line that wrote it.
 */
internal class HubSymbolParser(
    private val cursor: TokenCursor,
) {
    /**
     * A hub stream name: `<dataset>[.<SCOPE>]`, checked against the hub store's own naming rules.
     *
     * A scope can be a venue instrument (`DERIBIT:BTC-PERPETUAL`) or an issuer (`ISSUER:BRK-B`),
     * and the lexer splits those on `:` and `-`. Tokens written with no space between them are
     * one scope, so the parser re-joins them. The result is then held to the store's rules -- a
     * lower-case dotted dataset name and an upper-case scope with no dot -- because a name that
     * breaks them can never match a record, and failing here, at the line that wrote it, beats an
     * "unknown dataset" error at run start or a stream that silently reads nothing.
     */
    fun parse(): String {
        val start = cursor.peek()
        val parts = mutableListOf(nameSegment())
        while (cursor.peek().kind == TokenKind.DOT) {
            cursor.advance()
            parts.add(nameSegment())
        }
        var last = parts.removeAt(parts.lastIndex)
        while (adjacentScopePiece()) {
            last += cursor.advance().lexeme
        }
        parts.add(last)
        val symbol = parts.joinToString(".")
        val scopeCandidate = parts.last()
        val hasScope =
            parts.size > 2 && scopeCandidate.any { it.isLetter() } && scopeCandidate == scopeCandidate.uppercase()
        val dataset = if (hasScope) parts.dropLast(1).joinToString(".") else symbol
        if (!HUB_DATASET.matches(dataset)) {
            cursor.errorAt(
                start,
                "hub dataset '$dataset' is not a valid name: lower-case segments joined by '.', the first a domain " +
                    "(cal.us_high, rates.us.dgs10); a scope goes last in upper case (cal.us_high.USD, " +
                    "pos.funding.DERIBIT:BTC-PERPETUAL, corp.earnings.ISSUER:BRK-B)",
            )
        }
        if (hasScope && !HUB_SCOPE.matches(scopeCandidate)) {
            cursor.errorAt(
                start,
                "hub scope '$scopeCandidate' must be upper case A-Z 0-9 _ : - with at least one letter",
            )
        }
        return symbol
    }

    /** The next token continues the scope: written with no space, and a piece a scope may contain. */
    private fun adjacentScopePiece(): Boolean {
        val prev = cursor.peekAt(-1)
        val next = cursor.peek()
        if (next.line != prev.line || next.col != prev.col + prev.lexeme.length) return false
        return next.kind == TokenKind.COLON ||
            next.kind == TokenKind.MINUS ||
            (next.lexeme.isNotEmpty() && next.lexeme.all { it.isLetterOrDigit() || it == '_' })
    }

    /** One letters, digits or '_' segment; a scope may spell a reserved word (`USD`), so match on shape. */
    private fun nameSegment(): String {
        val token = cursor.peek()
        if (token.lexeme.isEmpty() || !token.lexeme.all { it.isLetterOrDigit() || it == '_' }) {
            cursor.error("expected a letters, digits or '_' name segment in a HUB symbol, got '${token.lexeme}'")
        }
        cursor.advance()
        return token.lexeme
    }
}

/** The hub store's dataset rule (hubread.record.DATASET_RE): a lower-case dotted name of 2-5 segments. */
private val HUB_DATASET = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+){1,4}$")

/** The hub store's scope rule (hubread.record.SCOPE_RE): upper case, no dot, at least one letter. */
private val HUB_SCOPE = Regex("^(?=[A-Z0-9_:-]*[A-Z])[A-Z0-9_:-]{1,64}$")
