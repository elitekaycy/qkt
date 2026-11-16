package com.qkt.dsl.parse

/**
 * A hub dataset or chain analytics name, which is dotted: `HUB:cal.high_impact`,
 * `HUB:cal.high_impact.USD`, `CHAIN:DERIBIT.BTC_USDC.atm_iv.30d`.
 *
 * Every other venue names an instrument with one identifier, so the general symbol rule is a
 * single IDENT. A hub dataset is addressed by a hierarchical name instead, and the lexer
 * splits on `.` because that character means field access everywhere else. Re-joining the
 * segments here keeps that meaning intact for every other stream while letting a hub alias
 * name what it actually needs to name.
 */
internal fun TokenCursor.dottedSymbol(): String {
    val parts = mutableListOf(nameSegment())
    while (peek().kind == TokenKind.DOT) {
        advance()
        parts.add(nameSegment())
    }
    return parts.joinToString(".")
}

/**
 * One segment of a hub dataset name, accepting a token that happens to spell a keyword.
 *
 * A scope is written the way the world writes it -- `USD`, `EUR` -- and several of those are
 * already reserved words elsewhere in the grammar (`SIZING 10000 USD`). Matching on the shape
 * of the lexeme rather than on the token kind keeps a dataset free to be named after the thing
 * it describes, without the DSL's own vocabulary leaking into what a dataset may be called.
 */
private fun TokenCursor.nameSegment(): String {
    val token = peek()
    require(token.lexeme.isNotEmpty() && token.lexeme.all { it.isLetterOrDigit() || it == '_' }) {
        "expected a letters, digits or '_' name segment in a dotted HUB, CHAIN or OPTIONS symbol, got '${token.lexeme}'"
    }
    advance()
    return token.lexeme
}
