package com.qkt.dsl.parse

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotKind
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal

/**
 * Parses an expression that starts with a name: a function or indicator call (`ema(btc.close, 20)`),
 * a stream field with an optional bar offset (`btc.close[3]`), a snapshot (`x@buy`), or a plain
 * reference to a LET, PARAM or iteration variable. The calendar predicates and rolling shorthands
 * are recognized by name here and handed to their own parsers.
 */
internal class IdentifierExprParser(
    private val cursor: TokenCursor,
    private val scope: ParseScope,
    private val expressionParser: ExpressionParser,
    private val aggregateParser: AggregateParser,
    private val clockExprParser: ClockExprParser,
) {
    /**
     * Parses the name-led expression at [t], the current token. [t] may also be OPEN, CLOSE,
     * LOG or FLOOR, which name functions or fields in expression position.
     */
    fun parseIdentifierExpr(t: Token): ExprAst {
        if ((scope.inStackLayerAt || scope.inOtoChildPrice) && t.kind == TokenKind.IDENT && t.lexeme == "entry") {
            cursor.advance()
            return StackEntryRef
        }
        val name = cursor.advance().lexeme
        return when {
            cursor.match(TokenKind.LPAREN) -> {
                val args = mutableListOf<ExprAst>()
                if (cursor.peek().kind != TokenKind.RPAREN) {
                    args.add(expressionParser.parseExpr())
                    while (cursor.match(TokenKind.COMMA)) args.add(expressionParser.parseExpr())
                }
                cursor.expect(TokenKind.RPAREN, "expected ')' after arguments")
                when {
                    // CALENDAR_WINDOW is a clock-reading boolean primitive (like NOW.*),
                    // not a pure numeric function or an indicator — it gets its own node.
                    name.equals("CALENDAR_WINDOW", ignoreCase = true) ->
                        clockExprParser.buildCalendarWindow(args, t)
                    name.equals("SESSION_WINDOW", ignoreCase = true) ->
                        clockExprParser.buildSessionWindow(args, t)
                    name.equals("LAST_TRADING_DAY_OF_MONTH", ignoreCase = true) ->
                        clockExprParser.buildLastTradingDayOfMonth(args, t)
                    // Rolling shorthand (#1130): avg(x, N) is mean(x) SINCE T-N, and
                    // count(cond, N) counts the last N bars where cond held.
                    name.equals("AVG", ignoreCase = true) -> aggregateParser.rollingShorthand(AggFn.MEAN, name, args, t)
                    name.equals(
                        "COUNT",
                        ignoreCase = true,
                    ) -> aggregateParser.rollingShorthand(AggFn.SUM, name, args, t)
                    // Scalar math functions (abs, sqrt, log, exp, pow, …) route through
                    // FuncCall — pure functions on numeric values, no warmup or per-bar state.
                    // Everything else stays IndicatorCall for the indicator-binding path.
                    com.qkt.dsl.stdlib.FuncRegistry
                        .has(name.uppercase()) ->
                        FuncCall(name.uppercase(), args)
                    else -> IndicatorCall(name, args)
                }
            }
            cursor.match(TokenKind.DOT) -> {
                val field = cursor.expectFieldName().lexeme
                barOffset(StreamFieldRef(name, field))
            }
            cursor.match(TokenKind.AT_SIGN) -> Ref(name, parseSnapshotKind())
            else -> Ref(name)
        }
    }

    /**
     * Optional `[n]` bar-offset suffix on a stream field, e.g. `btc.close[20]` is the close
     * 20 bars ago. `[0]` is the current bar (the bare field); `[n>0]` compiles to the `lag`
     * indicator, so it carries lag's `n + 1` bar warmup. A non-integer or negative offset is a
     * parse error.
     */
    private fun barOffset(base: ExprAst): ExprAst {
        if (cursor.peek().kind != TokenKind.LBRACKET) return base
        val open = cursor.advance()
        val nTok = cursor.expect(TokenKind.NUMBER, "expected an integer bar offset inside [ ]")
        cursor.expect(TokenKind.RBRACKET, "expected ']' to close the bar offset")
        val n = nTok.lexeme.toIntOrNull()
        if (n == null || n < 0) {
            cursor.errors +=
                ParseError(open.line, open.col, "bar offset must be a non-negative integer: ${nTok.lexeme}")
            return base
        }
        return if (n == 0) base else IndicatorCall("LAG", listOf(base, NumLit(java.math.BigDecimal(n))))
    }

    private fun parseSnapshotKind(): SnapshotKind {
        val t = cursor.peek()
        return when {
            t.kind == TokenKind.BUY -> {
                cursor.advance()
                SnapshotBuy
            }
            t.kind == TokenKind.SELL -> {
                cursor.advance()
                SnapshotSell
            }
            t.kind == TokenKind.OPEN -> {
                cursor.advance()
                SnapshotOpen
            }
            t.kind == TokenKind.IDENT && t.lexeme.equals("T", ignoreCase = true) -> {
                cursor.advance()
                cursor.expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    cursor.expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: cursor.error("expected positive integer after T-")
                SnapshotTPast(n)
            }
            else -> cursor.error("expected snapshot kind (buy/sell/open/T-N), got '${t.lexeme}'")
        }
    }
}
