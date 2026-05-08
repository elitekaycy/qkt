package com.qkt.dsl.parse

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotKind
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.Window
import java.math.BigDecimal

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

        val lets =
            if (peek().kind == TokenKind.LET) {
                tryParse { parseLet() } ?: emptyList()
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
                lets = lets,
                defaults = null,
                rules = emptyList(),
            ),
        )
    }

    private fun parseLet(): List<LetDecl> {
        val out = mutableListOf<LetDecl>()
        expect(TokenKind.LET, "expected LET")
        do {
            val name = expect(TokenKind.IDENT, "expected let name").lexeme
            expect(TokenKind.EQ, "expected '=' after let name")
            val expr = parseExpr()
            out.add(LetDecl(name, expr))
        } while (match(TokenKind.COMMA))
        return out
    }

    private fun parseExpr(): ExprAst = parseOrExpr()

    private fun parseOrExpr(): ExprAst {
        var lhs = parseAndExpr()
        while (peek().kind == TokenKind.OR) {
            advance()
            val rhs = parseAndExpr()
            lhs = BinaryOp(BinOp.OR, lhs, rhs)
        }
        return lhs
    }

    private fun parseAndExpr(): ExprAst {
        var lhs = parseNotExpr()
        while (peek().kind == TokenKind.AND) {
            advance()
            val rhs = parseNotExpr()
            lhs = BinaryOp(BinOp.AND, lhs, rhs)
        }
        return lhs
    }

    private fun parseNotExpr(): ExprAst {
        if (match(TokenKind.NOT)) return UnaryOp(UnOp.NOT, parseNotExpr())
        return parseCmpExpr()
    }

    private fun parseCmpExpr(): ExprAst {
        var lhs = parseAddExpr()
        while (true) {
            val k = peek().kind
            val op =
                when (k) {
                    TokenKind.GT -> Cmp.GT
                    TokenKind.LT -> Cmp.LT
                    TokenKind.GE -> Cmp.GE
                    TokenKind.LE -> Cmp.LE
                    TokenKind.EQEQ -> Cmp.EQ
                    TokenKind.NEQ -> Cmp.NE
                    else -> null
                }
            if (op != null) {
                advance()
                val rhs = parseAddExpr()
                lhs = CmpOp(op, lhs, rhs)
                continue
            }
            when (k) {
                TokenKind.BETWEEN -> {
                    advance()
                    val lo = parseAddExpr()
                    expect(TokenKind.AND, "expected AND between BETWEEN bounds")
                    val hi = parseAddExpr()
                    lhs = Between(lhs, lo, hi)
                }
                TokenKind.IN -> {
                    advance()
                    expect(TokenKind.LBRACKET, "expected '[' after IN")
                    val members = mutableListOf<ExprAst>()
                    if (peek().kind != TokenKind.RBRACKET) {
                        members.add(parseExpr())
                        while (match(TokenKind.COMMA)) members.add(parseExpr())
                    }
                    expect(TokenKind.RBRACKET, "expected ']' to close IN list")
                    lhs = InList(lhs, members)
                }
                TokenKind.CROSSES -> {
                    advance()
                    val dir =
                        when (peek().kind) {
                            TokenKind.ABOVE -> {
                                advance()
                                CrossDir.ABOVE
                            }
                            TokenKind.BELOW -> {
                                advance()
                                CrossDir.BELOW
                            }
                            else -> error("expected ABOVE or BELOW after CROSSES, got '${peek().lexeme}'")
                        }
                    val rhs = parseAddExpr()
                    lhs = Crosses(dir, lhs, rhs)
                }
                else -> return lhs
            }
        }
    }

    private fun parseAddExpr(): ExprAst {
        var lhs = parseMulExpr()
        while (peek().kind == TokenKind.PLUS || peek().kind == TokenKind.MINUS) {
            val op = if (advance().kind == TokenKind.PLUS) BinOp.ADD else BinOp.SUB
            val rhs = parseMulExpr()
            lhs = BinaryOp(op, lhs, rhs)
        }
        return lhs
    }

    private fun parseMulExpr(): ExprAst {
        var lhs = parseUnaryExpr()
        while (peek().kind == TokenKind.STAR || peek().kind == TokenKind.SLASH) {
            val op = if (advance().kind == TokenKind.STAR) BinOp.MUL else BinOp.DIV
            val rhs = parseUnaryExpr()
            lhs = BinaryOp(op, lhs, rhs)
        }
        return lhs
    }

    private fun parseUnaryExpr(): ExprAst {
        if (match(TokenKind.MINUS)) return UnaryOp(UnOp.NEG, parseUnaryExpr())
        return parsePrimary()
    }

    private fun parsePrimary(): ExprAst {
        val t = peek()
        return when (t.kind) {
            TokenKind.NUMBER -> {
                advance()
                NumLit(BigDecimal(t.lexeme))
            }
            TokenKind.STRING -> {
                advance()
                error("string literals are not allowed in expressions")
            }
            TokenKind.TRUE -> {
                advance()
                BoolLit(true)
            }
            TokenKind.FALSE -> {
                advance()
                BoolLit(false)
            }
            TokenKind.MAX, TokenKind.MIN, TokenKind.MEAN, TokenKind.SUM -> parseAggregate()
            TokenKind.CASE -> parseCaseWhen()
            TokenKind.IDENT, TokenKind.OPEN, TokenKind.CLOSE -> {
                val name = advance().lexeme
                when {
                    match(TokenKind.LPAREN) -> {
                        val args = mutableListOf<ExprAst>()
                        if (peek().kind != TokenKind.RPAREN) {
                            args.add(parseExpr())
                            while (match(TokenKind.COMMA)) args.add(parseExpr())
                        }
                        expect(TokenKind.RPAREN, "expected ')' after arguments")
                        IndicatorCall(name, args)
                    }
                    match(TokenKind.DOT) -> {
                        val field = expectFieldName().lexeme
                        StreamFieldRef(name, field)
                    }
                    match(TokenKind.AT_SIGN) -> Ref(name, parseSnapshotKind())
                    else -> Ref(name)
                }
            }
            TokenKind.LPAREN -> {
                advance()
                val e = parseExpr()
                expect(TokenKind.RPAREN, "expected ')'")
                e
            }
            else -> error("expected expression, got '${t.lexeme}'")
        }
    }

    private fun parseAggregate(): ExprAst {
        val fnTok = advance()
        val fn =
            when (fnTok.kind) {
                TokenKind.MAX -> AggFn.MAX
                TokenKind.MIN -> AggFn.MIN
                TokenKind.MEAN -> AggFn.MEAN
                TokenKind.SUM -> AggFn.SUM
                else -> error("unreachable")
            }
        expect(TokenKind.LPAREN, "expected '(' after ${fnTok.lexeme}")
        val series = parseExpr()
        expect(TokenKind.RPAREN, "expected ')' to close aggregate args")
        expect(TokenKind.SINCE, "expected SINCE after aggregate")
        val window = parseWindow()
        return Aggregate(fn, series, window)
    }

    private fun parseWindow(): Window =
        when (peek().kind) {
            TokenKind.OPEN -> {
                advance()
                SinceOpen
            }
            TokenKind.T -> {
                advance()
                expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: error("expected positive integer after T-")
                SinceTPast(n)
            }
            else -> error("expected OPEN or T-N for window, got '${peek().lexeme}'")
        }

    private fun parseSnapshotKind(): SnapshotKind {
        val t = peek()
        return when (t.kind) {
            TokenKind.BUY -> {
                advance()
                SnapshotBuy
            }
            TokenKind.SELL -> {
                advance()
                SnapshotSell
            }
            TokenKind.OPEN -> {
                advance()
                SnapshotOpen
            }
            TokenKind.T -> {
                advance()
                expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: error("expected positive integer after T-")
                SnapshotTPast(n)
            }
            else -> error("expected snapshot kind (buy/sell/open/T-N), got '${t.lexeme}'")
        }
    }

    private fun parseCaseWhen(): ExprAst {
        expect(TokenKind.CASE, "expected CASE")
        val branches = mutableListOf<Pair<ExprAst, ExprAst>>()
        while (peek().kind == TokenKind.WHEN) {
            advance()
            val cond = parseExpr()
            expect(TokenKind.THEN, "expected THEN in CASE branch")
            val body = parseExpr()
            branches.add(cond to body)
        }
        if (branches.isEmpty()) error("CASE requires at least one WHEN branch")
        val elseExpr =
            if (match(TokenKind.ELSE)) {
                parseExpr()
            } else {
                error("CASE requires an ELSE branch")
            }
        expect(TokenKind.END, "expected END to close CASE")
        return CaseWhen(branches, elseExpr)
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

    private fun expectFieldName(): Token {
        val t = peek()
        if (t.kind == TokenKind.IDENT || t.kind == TokenKind.OPEN || t.kind == TokenKind.CLOSE) {
            return advance()
        }
        error("expected field name, got '${t.lexeme}'")
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
