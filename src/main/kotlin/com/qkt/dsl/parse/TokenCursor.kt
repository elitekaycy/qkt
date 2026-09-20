package com.qkt.dsl.parse

/**
 * The parser's single read position over a lexed token list, and the errors recorded so far.
 *
 * Every grammar component reads through one shared cursor, so a component that fails mid-rule
 * leaves the position where recovery ([tryParse], [synchronize]) expects it, and every error
 * lands in one list. Errors are collected rather than thrown to the caller so one parse reports
 * every problem it can recover from.
 */
internal class TokenCursor(
    private val tokens: List<Token>,
) {
    private var pos = 0

    /** Errors recorded so far, in the order they were found. */
    val errors = mutableListOf<ParseError>()

    fun peek(): Token = tokens[pos]

    /** The token [offset] places past the current one; throws past the end of the list. */
    fun peekAt(offset: Int): Token = tokens[pos + offset]

    /** The token [offset] places past the current one, or null past the end of the list. */
    fun peekAtOrNull(offset: Int): Token? = tokens.getOrNull(pos + offset)

    fun advance(): Token = tokens[pos++]

    fun match(kind: TokenKind): Boolean =
        if (peek().kind == kind) {
            advance()
            true
        } else {
            false
        }

    fun expect(
        kind: TokenKind,
        msg: String,
    ): Token {
        if (peek().kind == kind) return advance()
        error("$msg, got '${peek().lexeme}'")
    }

    fun expectFieldName(): Token {
        val t = peek()
        // After a '.', any IDENT or keyword-with-identifier-shaped lexeme is a valid field name.
        if (t.kind == TokenKind.IDENT || isIdentLikeLexeme(t.lexeme)) {
            return advance()
        }
        error("expected field name, got '${t.lexeme}'")
    }

    fun expectName(msg: String): Token {
        val t = peek()
        if (t.kind == TokenKind.IDENT || isIdentLikeLexeme(t.lexeme)) {
            return advance()
        }
        error("$msg, got '${t.lexeme}'")
    }

    private fun isIdentLikeLexeme(s: String): Boolean {
        if (s.isEmpty()) return false
        val first = s[0]
        if (!(first.isLetter() || first == '_')) return false
        return s.all { it.isLetterOrDigit() || it == '_' }
    }

    /** Records a parse error at the current token and aborts the rule being parsed. */
    fun error(msg: String): Nothing {
        val t = peek()
        val e = ParseError(t.line, t.col, msg)
        errors.add(e)
        throw ParseException(e)
    }

    /** Skips ahead to the next token a top-level block can restart from. */
    fun synchronize() {
        while (peek().kind !in SYNC_KINDS) advance()
    }

    /** Runs [block]; on a parse error, recovers to the next sync point and returns null. */
    fun <T> tryParse(block: () -> T): T? {
        val start = pos
        return try {
            block()
        } catch (_: ParseException) {
            synchronize()
            // Recovery must always make progress: if the failing token is itself a sync
            // point, `synchronize` stays put and a caller's loop would spin forever (#1131).
            if (pos == start && peek().kind != TokenKind.EOF) advance()
            null
        }
    }

    /**
     * Records an error unless every token has been consumed. Without this, the first
     * unrecognized top-level token would silently end parsing — a strategy with a
     * typo (`RULE` for `RULES`) or a misplaced block would deploy "ok" with whole
     * sections missing.
     */
    fun requireEof() {
        if (peek().kind == TokenKind.EOF) return
        val t = peek()
        // Already reported at this exact token (e.g. an ordering error from parseRules):
        // a second message pointing at the same place adds noise, not information.
        if (errors.any { it.line == t.line && it.col == t.col }) return
        errors.add(
            ParseError(
                line = t.line,
                col = t.col,
                message =
                    "unexpected '${t.lexeme}' after the last recognized block — " +
                        "everything from here on would be silently ignored",
            ),
        )
    }

    private companion object {
        val SYNC_KINDS =
            setOf(
                TokenKind.DEFAULTS,
                TokenKind.SYMBOLS,
                TokenKind.LET,
                TokenKind.PARAM,
                TokenKind.RULES,
                TokenKind.WHEN,
                TokenKind.FOR,
                TokenKind.EOF,
            )
    }
}
