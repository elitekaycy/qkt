package com.qkt.dsl.parse

import com.qkt.dsl.ast.ExprAst
import java.math.BigDecimal

/**
 * Parses a `PORTFOLIO` file: its header, symbols, child strategy `IMPORT`s, optional regimes and
 * allocation, and the `RULES` that decide which children run. Errors inside one block are
 * recovered so the file reports every problem at once.
 */
internal class PortfolioParser(
    private val cursor: TokenCursor,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
    private val symbolsParser: SymbolsParser,
) {
    private val regimeParser = RegimeParser(cursor, expressionParser)
    private val allocationParser = AllocationParser(cursor, literalParser)

    internal fun parsePortfolio(): ParseResult<com.qkt.dsl.ast.PortfolioAst> {
        var name = "_unparsed"
        var version = 0
        var capital: java.math.BigDecimal? = null
        try {
            cursor.expect(TokenKind.PORTFOLIO, "expected PORTFOLIO")
            name = cursor.expect(TokenKind.IDENT, "expected portfolio name").lexeme
            cursor.expect(TokenKind.VERSION, "expected VERSION")
            val v = cursor.expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: cursor.error("VERSION must be an integer, got '${v.lexeme}'")
            if (cursor.peek().kind == TokenKind.CAPITAL) {
                cursor.advance()
                val capTok = cursor.expect(TokenKind.NUMBER, "expected number after CAPITAL")
                capital = capTok.lexeme.toBigDecimalOrNull()
                    ?: cursor.error("CAPITAL must be a number, got '${capTok.lexeme}'")
            }
        } catch (_: ParseException) {
            cursor.synchronize()
        }

        val streams =
            if (cursor.peek().kind == TokenKind.SYMBOLS) {
                // Portfolios don't support SYNCHRONIZE in this phase (#45) —
                // discard any parsed syncGroups.
                cursor.tryParse { symbolsParser.parseSymbols().streams } ?: emptyList()
            } else {
                emptyList()
            }

        val imports = mutableListOf<com.qkt.dsl.ast.ImportClause>()
        while (cursor.peek().kind == TokenKind.IMPORT) {
            cursor.tryParse { parseImport() }?.let { imports.add(it) }
        }

        val regimes =
            if (cursor.peek().kind == TokenKind.REGIMES) {
                cursor.tryParse { regimeParser.parseRegimes() }
            } else {
                null
            }

        val allocate =
            if (cursor.peek().kind == TokenKind.ALLOCATE) {
                cursor.tryParse { allocationParser.parseAllocate() }
            } else {
                null
            }

        val rules = mutableListOf<com.qkt.dsl.ast.PortfolioRule>()
        if (cursor.peek().kind == TokenKind.RULES) {
            cursor.advance()
            while (cursor.peek().kind == TokenKind.WHEN || cursor.peek().kind == TokenKind.RUN) {
                cursor.tryParse { parsePortfolioRule() }?.let { rules.add(it) }
            }
        }

        cursor.requireEof()
        if (cursor.errors.isNotEmpty()) return ParseResult.Failure(cursor.errors.toList())
        return try {
            ParseResult.Success(
                com.qkt.dsl.ast
                    .PortfolioAst(name, version, streams, imports, rules, capital, regimes, allocate),
            )
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure(
                listOf(
                    ParseError(
                        line = 0,
                        col = 0,
                        message = e.message ?: "PORTFOLIO validation failed",
                    ),
                ),
            )
        }
    }

    internal fun parseImport(): com.qkt.dsl.ast.ImportClause {
        cursor.expect(TokenKind.IMPORT, "expected IMPORT")
        val pathTok = cursor.expect(TokenKind.STRING, "expected import path string")
        cursor.expect(TokenKind.AS, "expected AS after import path")
        val alias = cursor.expect(TokenKind.IDENT, "expected alias").lexeme
        val hold =
            if (cursor.peek().kind == TokenKind.HOLD) {
                cursor.advance()
                true
            } else {
                false
            }
        return com.qkt.dsl.ast
            .ImportClause(path = pathTok.lexeme, alias = alias, hold = hold)
    }

    internal fun parsePortfolioRule(): com.qkt.dsl.ast.PortfolioRule =
        when (cursor.peek().kind) {
            TokenKind.WHEN -> {
                cursor.advance()
                val cond = expressionParser.parseExpr()
                cursor.expect(TokenKind.RUN, "expected RUN after WHEN expression")
                val alias = cursor.expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                val weight = parseOptionalWeight()
                com.qkt.dsl.ast
                    .WhenRun(cond, alias, weight, parseOptionalOverrides())
            }
            TokenKind.RUN -> {
                cursor.advance()
                val alias = cursor.expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                val weight = parseOptionalWeight()
                com.qkt.dsl.ast
                    .AlwaysRun(alias, weight, parseOptionalOverrides())
            }
            else -> cursor.error("expected WHEN or RUN, got '${cursor.peek().lexeme}'")
        }

    private fun parseOptionalWeight(): java.math.BigDecimal? =
        if (cursor.peek().kind == TokenKind.WEIGHT) {
            cursor.advance()
            val tok = cursor.expect(TokenKind.NUMBER, "expected number after WEIGHT")
            tok.lexeme.toBigDecimalOrNull() ?: cursor.error("WEIGHT must be a number, got '${tok.lexeme}'")
        } else {
            null
        }

    private fun parseOptionalOverrides(): Map<String, ExprAst> {
        if (cursor.peek().kind != TokenKind.OVERRIDE) return emptyMap()
        cursor.advance()
        cursor.expect(TokenKind.LBRACE, "expected '{' after OVERRIDE")
        val out = LinkedHashMap<String, ExprAst>()
        if (cursor.peek().kind != TokenKind.RBRACE) {
            do {
                val key = cursor.expect(TokenKind.IDENT, "expected override key").lexeme
                if (out.containsKey(key)) cursor.error("duplicate OVERRIDE key '$key'")
                cursor.expect(TokenKind.EQ, "expected '=' after override key")
                out[key] = literalParser.parseLiteral()
            } while (cursor.match(TokenKind.COMMA))
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close OVERRIDE")
        return out
    }
}
