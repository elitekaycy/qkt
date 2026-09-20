package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchCloseBeyond
import com.qkt.dsl.ast.LatchConfirm
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchFirstTick
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchOrder
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.LatchTimeInBreach
import com.qkt.dsl.ast.SizingAst

/**
 * Parses a `LATCH` breakout action: the offset and reference level, the arm window, the optional
 * `CONFIRM` rule, and the `{ ENTER ... }` entries that fire once the level breaks. Entry prices and
 * bracket legs are written `WITH`/`AGAINST` the break direction, which is only known when it fires.
 */
internal class LatchParser(
    private val cursor: TokenCursor,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
    private val sizingParser: SizingParser,
    private val orderTypeParser: OrderTypeParser,
) {
    fun parseLatch(): ActionAst {
        cursor.expect(TokenKind.LATCH, "expected LATCH")
        val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after LATCH").lexeme
        cursor.expect(TokenKind.OFFSET, "expected OFFSET after LATCH stream")
        val offset = expressionParser.parseExpr()
        val reference =
            if (cursor.match(TokenKind.FROM)) {
                expressionParser.parseExpr()
            } else {
                null
            }
        cursor.expect(TokenKind.ARM, "expected ARM <duration> in LATCH")
        val armWindow = literalParser.parseDuration()
        val name =
            if (cursor.match(TokenKind.AS)) {
                cursor.expect(TokenKind.IDENT, "expected name after AS").lexeme
            } else {
                null
            }
        val confirm =
            if (cursor.match(TokenKind.CONFIRM)) {
                parseLatchConfirm()
            } else {
                LatchFirstTick
            }
        cursor.expect(TokenKind.LBRACE, "expected '{' to open LATCH block")
        val entries = mutableListOf(parseLatchEntry())

        while (cursor.match(TokenKind.SEMICOLON)) {
            entries.add(parseLatchEntry())
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close LATCH block")
        return Latch(stream, BreakOffset(reference, offset), armWindow, name, entries, confirm)
    }

    private fun parseLatchConfirm(): LatchConfirm =
        when (cursor.peek().kind) {
            TokenKind.CLOSE_BEYOND -> {
                cursor.advance()
                LatchCloseBeyond
            }
            TokenKind.TIME_IN_BREACH -> {
                cursor.advance()
                LatchTimeInBreach(literalParser.parseDuration())
            }
            TokenKind.RETEST_HOLD -> {
                cursor.advance()
                val distance = expressionParser.parseExpr()
                cursor.expect(TokenKind.WITHIN, "expected WITHIN after RETEST_HOLD distance")
                LatchRetestHold(distance, literalParser.parseDuration())
            }
            else -> cursor.error("expected CLOSE_BEYOND, TIME_IN_BREACH, or RETEST_HOLD after CONFIRM")
        }

    private fun parseLatchEntry(): LatchEntry {
        cursor.expect(TokenKind.ENTER, "expected ENTER in LATCH block")
        val entryStream =
            if (cursor.match(TokenKind.ON)) {
                cursor.expect(TokenKind.IDENT, "expected stream alias after ENTER ON").lexeme
            } else {
                null
            }
        val order = parseLatchOrder()
        var bracket: LatchBracket? = null
        var sizing: SizingAst? = null
        var expire: DurationAst? = null
        loop@ while (true) {
            when (cursor.peek().kind) {
                TokenKind.BRACKET -> {
                    cursor.advance()
                    bracket = parseLatchBracket()
                }
                TokenKind.SIZING -> {
                    cursor.advance()
                    sizing = sizingParser.parseSizing()
                }
                TokenKind.EXPIRE -> {
                    cursor.advance()
                    expire = literalParser.parseDuration()
                }
                else -> break@loop
            }
        }
        return LatchEntry(order, bracket, sizing, expire, entryStream)
    }

    private fun parseLatchOrder(): LatchOrder =
        when (cursor.peek().kind) {
            TokenKind.MARKET -> {
                cursor.advance()
                LatchMarket
            }
            TokenKind.LIMIT -> {
                cursor.advance()
                LatchLimit(orderTypeParser.parseDirRel())
            }
            TokenKind.STOP -> {
                cursor.advance()
                LatchStop(orderTypeParser.parseDirRel())
            }
            else -> cursor.error("expected MARKET/LIMIT/STOP after ENTER, got '${cursor.peek().lexeme}'")
        }

    private fun parseLatchBracket(): LatchBracket {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open BRACKET block")
        var stopLoss: DirRel? = null
        var takeProfit: DirRel? = null
        do {
            // Accept both `STOP LOSS` / `TAKE PROFIT` and the single-token `STOP_LOSS` / `TAKE_PROFIT`.
            when (val tok = cursor.peek().kind) {
                TokenKind.STOP, TokenKind.STOP_LOSS -> {
                    cursor.advance()
                    if (tok == TokenKind.STOP) cursor.expect(TokenKind.LOSS, "expected LOSS after STOP")
                    stopLoss = orderTypeParser.parseDirRel()
                }
                TokenKind.TAKE, TokenKind.TAKE_PROFIT -> {
                    cursor.advance()
                    if (tok == TokenKind.TAKE) cursor.expect(TokenKind.PROFIT, "expected PROFIT after TAKE")
                    takeProfit = orderTypeParser.parseDirRel()
                }
                else -> cursor.error("expected STOP LOSS or TAKE PROFIT in BRACKET, got '${cursor.peek().lexeme}'")
            }
        } while (cursor.match(TokenKind.COMMA))
        cursor.expect(TokenKind.RBRACE, "expected '}' to close BRACKET block")
        return LatchBracket(stopLoss, takeProfit)
    }
}
