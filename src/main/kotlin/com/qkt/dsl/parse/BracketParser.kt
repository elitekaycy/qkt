package com.qkt.dsl.parse

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.StopStepAst
import com.qkt.dsl.ast.TimeTightenAst
import java.math.BigDecimal

/**
 * Parses the protective children attached to an entry: a `BRACKET { STOP LOSS ..., TAKE PROFIT ... }`
 * block, an `OCO { STOP AT ..., LIMIT AT ... }` pair, and the child-price forms each leg takes
 * (`AT`, `BY`, `PCT`, `RR`, armed `TRAILING`, stepped and time-tightened stops).
 */
internal class BracketParser(
    private val cursor: TokenCursor,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
) {
    internal fun parseBracket(): BracketAst {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open BRACKET block")
        var stopLoss: ChildPriceAst? = null
        var takeProfit: ChildPriceAst? = null
        do {
            // Accept both the two-word `STOP LOSS` / `TAKE PROFIT` and the single-token
            // `STOP_LOSS` / `TAKE_PROFIT` spellings (the latter is what DEFAULTS uses).
            when (val tok = cursor.peek().kind) {
                TokenKind.STOP, TokenKind.STOP_LOSS -> {
                    cursor.advance()
                    if (tok == TokenKind.STOP) cursor.expect(TokenKind.LOSS, "expected LOSS after STOP")
                    stopLoss = parseChildPrice()
                }
                TokenKind.TAKE, TokenKind.TAKE_PROFIT -> {
                    cursor.advance()
                    if (tok == TokenKind.TAKE) cursor.expect(TokenKind.PROFIT, "expected PROFIT after TAKE")
                    if (cursor.peek().kind == TokenKind.TRAILING) {
                        cursor.error(
                            "TAKE PROFIT TRAILING is not supported — TRAILING is stop-only " +
                                "(armed trail). Use TAKE PROFIT AT/BY/PCT/RR.",
                        )
                    }
                    takeProfit = parseChildPrice()
                }
                else -> cursor.error("expected STOP LOSS or TAKE PROFIT in BRACKET, got '${cursor.peek().lexeme}'")
            }
        } while (cursor.match(TokenKind.COMMA))
        cursor.expect(TokenKind.RBRACE, "expected '}' to close BRACKET block")
        return BracketAst(stopLoss, takeProfit)
    }

    internal fun parseOco(): OcoAst {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open OCO block")
        var stop: ChildPriceAst? = null
        var limit: ChildPriceAst? = null
        do {
            when (cursor.peek().kind) {
                TokenKind.STOP -> {
                    cursor.advance()
                    cursor.expect(TokenKind.AT, "expected AT after STOP in OCO")
                    stop = ChildAt(expressionParser.parseExpr())
                }
                TokenKind.LIMIT -> {
                    cursor.advance()
                    cursor.expect(TokenKind.AT, "expected AT after LIMIT in OCO")
                    limit = ChildAt(expressionParser.parseExpr())
                }
                else -> cursor.error("expected STOP AT or LIMIT AT in OCO, got '${cursor.peek().lexeme}'")
            }
        } while (cursor.match(TokenKind.COMMA))
        cursor.expect(TokenKind.RBRACE, "expected '}' to close OCO block")
        val s = stop ?: cursor.error("OCO requires a STOP AT child")
        val l = limit ?: cursor.error("OCO requires a LIMIT AT child")
        return OcoAst(s, l)
    }

    internal fun parseChildPrice(): ChildPriceAst =
        when (cursor.peek().kind) {
            TokenKind.AT -> {
                cursor.advance()
                ChildAt(expressionParser.parseExpr())
            }
            TokenKind.BY -> {
                cursor.advance()
                val distance = expressionParser.parseExpr()
                when {
                    cursor.match(TokenKind.PCT) -> ChildPct(distance)
                    cursor.peek().kind == TokenKind.STEP ->
                        ChildBy(
                            distance = distance,
                            ratchet = parseSteppedStop(),
                        )
                    cursor.match(TokenKind.TIGHTEN) ->
                        ChildBy(
                            distance = distance,
                            ratchet = parseTimeTighten(),
                        )
                    else -> ChildBy(distance)
                }
            }
            TokenKind.PCT -> {
                cursor.advance()
                ChildPct(expressionParser.parseExpr())
            }
            TokenKind.RR -> {
                cursor.advance()
                ChildRr(expressionParser.parseExpr())
            }
            TokenKind.TRAILING -> {
                cursor.advance()
                val distance = expressionParser.parseExpr()
                cursor.expect(TokenKind.AFTER, "expected AFTER after TRAILING <distance>")
                cursor.expect(TokenKind.MFE, "expected MFE after AFTER")
                cursor.expect(TokenKind.GE, "expected '>=' after MFE")
                val threshold = expressionParser.parseExpr()
                ChildArmedTrail(distance, threshold)
            }
            else -> cursor.error("expected child price (AT/BY/PCT/RR/TRAILING), got '${cursor.peek().lexeme}'")
        }

    private fun parseSteppedStop(): com.qkt.dsl.ast.SteppedStopAst {
        val steps = mutableListOf<com.qkt.dsl.ast.StopStepAst>()
        while (cursor.match(TokenKind.STEP)) {
            cursor.expect(TokenKind.TO, "expected TO after STEP")
            val target = cursor.expect(TokenKind.IDENT, "expected BREAKEVEN or ENTRY after STEP TO")
            if (!target.lexeme.equals("BREAKEVEN", ignoreCase = true) &&
                !target.lexeme.equals("ENTRY", ignoreCase = true)
            ) {
                cursor.error("expected BREAKEVEN or ENTRY after STEP TO")
            }
            val profitDistance =
                if (cursor.match(TokenKind.PLUS)) {
                    expressionParser.parseExpr()
                } else {
                    NumLit(BigDecimal.ZERO)
                }
            cursor.expect(TokenKind.AFTER, "expected AFTER after step target")
            cursor.expect(TokenKind.MFE, "expected MFE after AFTER")
            cursor.expect(TokenKind.GE, "expected '>=' after MFE")
            steps +=
                StopStepAst(
                    mfeThreshold = expressionParser.parseExpr(),
                    profitDistance = profitDistance,
                )
        }
        return SteppedStopAst(steps)
    }

    private fun parseTimeTighten(): TimeTightenAst {
        cursor.expect(TokenKind.BY, "expected BY after TIGHTEN")
        val tightenBy = expressionParser.parseExpr()
        cursor.expect(TokenKind.EVERY, "expected EVERY after TIGHTEN BY <distance>")
        val interval = literalParser.parseDuration()
        cursor.expect(TokenKind.FLOOR, "expected FLOOR after tightening interval")
        return TimeTightenAst(
            tightenBy = tightenBy,
            interval = interval,
            floorDistance = expressionParser.parseExpr(),
        )
    }
}
