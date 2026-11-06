package com.qkt.dsl.parse

/**
 * A venue symbol after `BROKER:`: one identifier, or one that ends in digits and continues, with nothing between, with
 * `.` and more digits: a decimal strike such as `KXHIGHNY_26NOV06_B73.5`.
 *
 * The lexer reads that as an identifier, a `.` and a number, because `.` is field access everywhere else. They are
 * joined back here only when the three touch, so `X_1 . 5` is still the parse error it was, and every symbol a file
 * could write before (always one identifier) parses to the same string.
 */
internal fun TokenCursor.venueSymbol(): String {
    val name = expect(TokenKind.IDENT, "expected symbol after ':'")
    val dot = peek()
    val digits = peekAtOrNull(1) ?: return name.lexeme
    val strike =
        name.lexeme.last().isDigit() &&
            dot.kind == TokenKind.DOT &&
            follows(name, dot) &&
            digits.kind == TokenKind.NUMBER &&
            digits.lexeme.all(Char::isDigit) &&
            follows(dot, digits)
    if (!strike) return name.lexeme
    advance()
    advance()
    return "${name.lexeme}.${digits.lexeme}"
}

/** True when [next] starts on the same line right where [prev] ends. */
private fun follows(
    prev: Token,
    next: Token,
): Boolean = next.line == prev.line && next.col == prev.col + prev.lexeme.length
