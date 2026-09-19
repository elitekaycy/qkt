package com.qkt.dsl.parse

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StringLit

/**
 * Reads the single-token literal forms the grammar shares: constant values (`PARAM`, `OVERRIDE`),
 * duration literalParser (`30m`, `1h`) and `EVERY` timeframes. Durations are converted to milliseconds
 * here, once, so every block that takes a duration agrees on the unit.
 */
internal class LiteralParser(
    private val cursor: TokenCursor,
) {
    fun parseLiteral(): ExprAst {
        val negate = cursor.match(TokenKind.MINUS)
        return when (cursor.peek().kind) {
            TokenKind.NUMBER -> {
                val t = cursor.advance()
                val n = t.lexeme.toBigDecimalOrNull() ?: cursor.error("expected a number literal, got '${t.lexeme}'")
                NumLit(if (negate) n.negate() else n)
            }
            TokenKind.TRUE -> {
                cursor.advance()
                if (negate) cursor.error("cannot negate a boolean")
                BoolLit(true)
            }
            TokenKind.FALSE -> {
                cursor.advance()
                if (negate) cursor.error("cannot negate a boolean")
                BoolLit(false)
            }
            TokenKind.STRING -> {
                val t = cursor.advance()
                if (negate) cursor.error("cannot negate a string")
                StringLit(t.lexeme)
            }
            else ->
                cursor.error(
                    "expected a literal value (number, TRUE/FALSE, or string), got '${cursor.peek().lexeme}'",
                )
        }
    }

    internal fun parseDuration(): DurationAst {
        val tok = cursor.expect(TokenKind.DURATION, "expected duration literal (e.g., 1h, 30m)")
        val lex = tok.lexeme
        val n =
            lex.dropLast(1).toLongOrNull()
                ?: cursor.error("invalid duration literal '$lex'")
        val unit = lex.last()
        val millis =
            when (unit) {
                's' -> n * 1_000L
                'm' -> n * 60_000L
                'h' -> n * 3_600_000L
                'd' -> n * 86_400_000L
                else -> cursor.error("unknown duration unit '$unit' in '$lex'")
            }
        return DurationAst(millis)
    }

    internal fun parseWithin(): DurationAst {
        cursor.expect(TokenKind.WITHIN, "expected WITHIN")
        return parseDuration()
    }

    /** Parse an `EVERY` timeframe value: a `DURATION` token (`1h`) or a `<number><unit>` pair. */
    fun parseTimeframe(): String =
        if (cursor.peek().kind == TokenKind.DURATION) {
            cursor.advance().lexeme
        } else {
            val tfNum = cursor.expect(TokenKind.NUMBER, "expected timeframe count").lexeme
            val tfUnit = cursor.expect(TokenKind.IDENT, "expected timeframe unit (s/m/h/d)").lexeme
            "$tfNum$tfUnit"
        }
}
