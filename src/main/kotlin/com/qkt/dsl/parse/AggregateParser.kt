package com.qkt.dsl.parse

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.Window
import java.math.BigDecimal

/**
 * Parses windowed aggregateParser over a series: `max(x) SINCE OPEN`, `mean(x) SINCE T-20`, and the
 * rolling shorthands `sum(x, N)`, `avg(x, N)` and `count(cond, N)` that desugar to the same
 * [Aggregate] node, so the compiler sees one form.
 */
internal class AggregateParser(
    private val cursor: TokenCursor,
    private val expressionParser: ExpressionParser,
) {
    fun parseAggregate(): ExprAst {
        val fnTok = cursor.advance()
        val fn =
            when (fnTok.kind) {
                TokenKind.MAX -> AggFn.MAX
                TokenKind.MIN -> AggFn.MIN
                TokenKind.MEAN -> AggFn.MEAN
                TokenKind.SUM -> AggFn.SUM
                else -> cursor.error("unreachable")
            }
        cursor.expect(TokenKind.LPAREN, "expected '(' after ${fnTok.lexeme}")
        val series = expressionParser.parseExpr()
        // `sum(x, N)` / `mean(x, N)` is shorthand for `sum(x) SINCE T-N` (#1130).
        if (cursor.match(TokenKind.COMMA)) {
            val window = rollingWindowArg(fnTok.lexeme)
            cursor.expect(TokenKind.RPAREN, "expected ')' to close ${fnTok.lexeme}(<expr>, N)")
            return Aggregate(fn, series, window)
        }
        cursor.expect(TokenKind.RPAREN, "expected ')' to close aggregate args")
        cursor.expect(TokenKind.SINCE, "expected SINCE after aggregate")
        val window = parseWindow()
        return Aggregate(fn, series, window)
    }

    /** The `N` of a rolling shorthand: a positive integer literal, the same rule as `T-N`. */
    private fun rollingWindowArg(fnName: String): SinceTPast {
        val tok = cursor.expect(TokenKind.NUMBER, "expected a positive integer window after $fnName(<expr>,")
        val n = tok.lexeme.toIntOrNull()
        if (n == null ||
            n <= 0
        ) {
            cursor.error("$fnName(<expr>, N) window must be a positive integer, got '${tok.lexeme}'")
        }
        return SinceTPast(n)
    }

    fun rollingShorthand(
        fn: AggFn,
        name: String,
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        if (args.size != 2) {
            cursor.errors += ParseError(at.line, at.col, "${name.lowercase()} expects (<expr>, N)")
            return NumLit(java.math.BigDecimal.ZERO)
        }
        val n = (args[1] as? NumLit)?.value
        val window = n?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 }?.toInt()
        if (window == null) {
            cursor.errors +=
                ParseError(at.line, at.col, "${name.lowercase()}(<expr>, N) window must be a positive integer")
            return NumLit(java.math.BigDecimal.ZERO)
        }
        val series =
            if (fn == AggFn.SUM) {
                CaseWhen(listOf(args[0] to NumLit(java.math.BigDecimal.ONE)), NumLit(java.math.BigDecimal.ZERO))
            } else {
                args[0]
            }
        return Aggregate(fn, series, SinceTPast(window))
    }

    fun parseAggregateOrFunction(): ExprAst {
        val fnTok = cursor.advance()
        cursor.expect(TokenKind.LPAREN, "expected '(' after ${fnTok.lexeme}")
        val args = mutableListOf(expressionParser.parseExpr())
        while (cursor.match(TokenKind.COMMA)) args.add(expressionParser.parseExpr())
        cursor.expect(TokenKind.RPAREN, "expected ')' after arguments")

        if (cursor.match(TokenKind.SINCE)) {
            if (args.size != 1) {
                cursor.errors +=
                    ParseError(fnTok.line, fnTok.col, "${fnTok.lexeme} aggregate expects exactly one series")
            }
            val fn = if (fnTok.kind == TokenKind.MAX) AggFn.MAX else AggFn.MIN
            return Aggregate(fn, args.first(), parseWindow())
        }
        return FuncCall(fnTok.lexeme.uppercase(), args)
    }

    private fun parseWindow(): Window {
        val t = cursor.peek()
        return when {
            t.kind == TokenKind.OPEN -> {
                cursor.advance()
                SinceOpen
            }
            t.kind == TokenKind.IDENT && t.lexeme.equals("T", ignoreCase = true) -> {
                cursor.advance()
                cursor.expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    cursor.expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: cursor.error("expected positive integer after T-")
                SinceTPast(n)
            }
            else -> cursor.error("expected OPEN or T-N for window, got '${t.lexeme}'")
        }
    }
}
