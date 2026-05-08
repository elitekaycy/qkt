package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst

class Parser(
    private val tokens: List<Token>,
) {
    private var pos = 0
    private val errors = mutableListOf<ParseError>()

    fun parseStrategy(): ParseResult<StrategyAst> {
        var name = "_unparsed"
        var version = 0
        try {
            expect(TokenKind.STRATEGY, "expected STRATEGY")
            name = expect(TokenKind.IDENT, "expected strategy name").lexeme
            expect(TokenKind.VERSION, "expected VERSION")
            val v = expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: error("VERSION must be an integer, got '${v.lexeme}'")
        } catch (_: ParseException) {
            synchronize()
        }

        if (errors.isNotEmpty()) return ParseResult.Failure(errors.toList())
        return ParseResult.Success(
            StrategyAst(
                name = name,
                version = version,
                streams = emptyList(),
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules = emptyList(),
            ),
        )
    }

    private fun peek(): Token = tokens[pos]

    private fun advance(): Token = tokens[pos++]

    private fun match(kind: TokenKind): Boolean =
        if (peek().kind == kind) {
            advance()
            true
        } else {
            false
        }

    private fun expect(
        kind: TokenKind,
        msg: String,
    ): Token {
        if (peek().kind == kind) return advance()
        error("$msg, got '${peek().lexeme}'")
    }

    private fun error(msg: String): Nothing {
        val t = peek()
        val e = ParseError(t.line, t.col, msg)
        errors.add(e)
        throw ParseException(e)
    }

    private fun synchronize() {
        while (peek().kind !in SYNC_KINDS) advance()
    }

    companion object {
        private val SYNC_KINDS =
            setOf(
                TokenKind.DEFAULTS,
                TokenKind.SYMBOLS,
                TokenKind.LET,
                TokenKind.RULES,
                TokenKind.WHEN,
                TokenKind.FOR,
                TokenKind.EOF,
            )
    }
}
