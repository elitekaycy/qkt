package com.qkt.dsl.parse

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StringLit
import java.math.BigDecimal

/**
 * Parses one primary expression: a literal, a parenthesized expression, `CASE WHEN`, or a
 * reference to market data, account state or the clockExprParser. Operators are handled a level up in
 * [ExpressionParser]; this class decides only what a single operand is.
 */
internal class PrimaryParser(
    private val cursor: TokenCursor,
    private val scope: ParseScope,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
) {
    private val aggregateParser = AggregateParser(cursor, expressionParser)
    private val clockExprParser = ClockExprParser(cursor)
    private val stateRefParser = StateRefParser(cursor)
    private val identifierExprParser =
        IdentifierExprParser(
            cursor,
            scope,
            expressionParser,
            aggregateParser,
            clockExprParser,
        )

    fun parsePrimary(): ExprAst {
        val t = cursor.peek()
        return when (t.kind) {
            TokenKind.NUMBER -> {
                cursor.advance()
                NumLit(BigDecimal(t.lexeme))
            }
            TokenKind.DURATION -> {
                val d = literalParser.parseDuration()
                NumLit(BigDecimal.valueOf(d.millis))
            }
            TokenKind.STRING -> {
                cursor.advance()
                StringLit(t.lexeme)
            }
            TokenKind.TRUE -> {
                cursor.advance()
                BoolLit(true)
            }
            TokenKind.FALSE -> {
                cursor.advance()
                BoolLit(false)
            }
            TokenKind.ENTRY_QTY -> {
                cursor.advance()
                com.qkt.dsl.ast.EntryQty
            }
            TokenKind.EXIT,
            TokenKind.ACCOUNT,
            TokenKind.STREAK,
            TokenKind.TRADES,
            TokenKind.COOLDOWN,
            TokenKind.SEQUENCE,
            TokenKind.POSITION,
            TokenKind.POSITION_AVG_PRICE,
            TokenKind.OPEN_ORDERS,
            -> stateRefParser.parseStateRef(t)
            TokenKind.MAX, TokenKind.MIN -> aggregateParser.parseAggregateOrFunction()
            TokenKind.MEAN, TokenKind.SUM -> aggregateParser.parseAggregate()
            TokenKind.CASE -> parseCaseWhen()
            TokenKind.SYMBOL -> {
                cursor.advance()
                Ref("__SYMBOL__")
            }
            TokenKind.NOW -> clockExprParser.parseNowAccessor()
            // LOG and FLOOR are also reserved action/order keywords. In expression
            // position their parenthesized forms bind to registered math functions.
            TokenKind.IDENT, TokenKind.OPEN, TokenKind.CLOSE, TokenKind.LOG, TokenKind.FLOOR ->
                identifierExprParser.parseIdentifierExpr(t)
            TokenKind.LPAREN -> {
                cursor.advance()
                val e = expressionParser.parseExpr()
                cursor.expect(TokenKind.RPAREN, "expected ')'")
                e
            }
            else -> cursor.error("expected expression, got '${t.lexeme}'")
        }
    }

    private fun parseCaseWhen(): ExprAst {
        cursor.expect(TokenKind.CASE, "expected CASE")
        val branches = mutableListOf<Pair<ExprAst, ExprAst>>()
        while (cursor.peek().kind == TokenKind.WHEN) {
            cursor.advance()
            val cond = expressionParser.parseExpr()
            cursor.expect(TokenKind.THEN, "expected THEN in CASE branch")
            val body = expressionParser.parseExpr()
            branches.add(cond to body)
        }
        if (branches.isEmpty()) cursor.error("CASE requires at least one WHEN branch")
        val elseExpr =
            if (cursor.match(TokenKind.ELSE)) {
                expressionParser.parseExpr()
            } else {
                cursor.error("CASE requires an ELSE branch")
            }
        cursor.expect(TokenKind.END, "expected END to close CASE")
        return CaseWhen(branches, elseExpr)
    }
}
