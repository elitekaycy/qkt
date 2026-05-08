package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl

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

        val streams =
            if (peek().kind == TokenKind.SYMBOLS) {
                tryParse { parseSymbols() } ?: emptyList()
            } else {
                emptyList()
            }

        if (errors.isNotEmpty()) return ParseResult.Failure(errors.toList())
        return ParseResult.Success(
            StrategyAst(
                name = name,
                version = version,
                streams = streams,
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules = emptyList(),
            ),
        )
    }

    private fun parseSymbols(): List<StreamDecl> {
        val out = mutableListOf<StreamDecl>()
        expect(TokenKind.SYMBOLS, "expected SYMBOLS")
        do {
            val alias = expect(TokenKind.IDENT, "expected stream alias").lexeme
            expect(TokenKind.EQ, "expected '=' after stream alias")
            val broker = expect(TokenKind.IDENT, "expected broker prefix").lexeme
            expect(TokenKind.COLON, "expected ':' between broker and symbol")
            val symbol = expect(TokenKind.IDENT, "expected symbol after ':'").lexeme
            expect(TokenKind.EVERY, "expected EVERY")
            val tfNum = expect(TokenKind.NUMBER, "expected timeframe count").lexeme
            val tfUnit = expect(TokenKind.IDENT, "expected timeframe unit (s/m/h/d)").lexeme
            out.add(
                StreamDecl(
                    alias = alias,
                    broker = broker,
                    symbol = symbol,
                    timeframe = "$tfNum$tfUnit",
                ),
            )
        } while (match(TokenKind.COMMA))
        return out
    }

    private inline fun <T> tryParse(block: () -> T): T? =
        try {
            block()
        } catch (_: ParseException) {
            synchronize()
            null
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
