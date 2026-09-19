package com.qkt.dsl.parse

import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.ExitRelativeLimit
import com.qkt.dsl.ast.ExitRelativeStop
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct

/**
 * Parses how an order rests at the venue: its type (`MARKET`, `LIMIT AT`, `STOP AT`,
 * `STOP ... LIMIT AT`, `TRAILING`) and its time in force (`GTC`, `IOC`, `FOK`, `DAY`, `GTD`).
 * Inside an exit hook a LIMIT or STOP may instead be priced `WITH`/`AGAINST` the exit, relative
 * to the exit fill.
 */
internal class OrderTypeParser(
    private val cursor: TokenCursor,
    private val scope: ParseScope,
    private val expressionParser: ExpressionParser,
) {
    internal fun parseOrderType(): OrderTypeAst =
        when (cursor.peek().kind) {
            TokenKind.MARKET -> {
                cursor.advance()
                Market
            }
            TokenKind.LIMIT -> {
                cursor.advance()
                if (scope.inExitHook && cursor.peek().kind in setOf(TokenKind.WITH, TokenKind.AGAINST)) {
                    ExitRelativeLimit(parseDirRel())
                } else {
                    cursor.expect(TokenKind.AT, "expected AT after LIMIT")
                    Limit(expressionParser.parseExpr())
                }
            }
            TokenKind.STOP -> {
                cursor.advance()
                if (scope.inExitHook && cursor.peek().kind in setOf(TokenKind.WITH, TokenKind.AGAINST)) {
                    ExitRelativeStop(parseDirRel())
                } else {
                    cursor.expect(TokenKind.AT, "expected AT after STOP")
                    val stopPrice = expressionParser.parseExpr()
                    if (cursor.peek().kind == TokenKind.LIMIT) {
                        cursor.advance()
                        cursor.expect(TokenKind.AT, "expected AT after LIMIT")
                        StopLimit(stopPrice, expressionParser.parseExpr())
                    } else {
                        Stop(stopPrice)
                    }
                }
            }
            TokenKind.TRAILING -> {
                cursor.advance()
                when (cursor.peek().kind) {
                    TokenKind.BY -> {
                        cursor.advance()
                        TrailingBy(expressionParser.parseExpr())
                    }
                    TokenKind.PCT -> {
                        cursor.advance()
                        TrailingPct(expressionParser.parseExpr())
                    }
                    else -> cursor.error("expected BY or PCT after TRAILING, got '${cursor.peek().lexeme}'")
                }
            }
            else -> cursor.error("expected order type (MARKET/LIMIT/STOP/TRAILING), got '${cursor.peek().lexeme}'")
        }

    internal fun parseTif(): TifAst =
        when (cursor.peek().kind) {
            TokenKind.GTC -> {
                cursor.advance()
                Gtc
            }
            TokenKind.IOC -> {
                cursor.advance()
                Ioc
            }
            TokenKind.FOK -> {
                cursor.advance()
                Fok
            }
            TokenKind.DAY -> {
                cursor.advance()
                Day
            }
            TokenKind.GTD -> {
                cursor.advance()
                cursor.match(TokenKind.UNTIL)
                Gtd(expressionParser.parseExpr())
            }
            else -> cursor.error("expected TIF (GTC/IOC/FOK/DAY/GTD), got '${cursor.peek().lexeme}'")
        }

    fun parseDirRel(): DirRel {
        val sense =
            when (cursor.peek().kind) {
                TokenKind.WITH -> DirSense.WITH
                TokenKind.AGAINST -> DirSense.AGAINST
                TokenKind.RETRACE -> DirSense.AGAINST
                else -> cursor.error("expected WITH/AGAINST/RETRACE, got '${cursor.peek().lexeme}'")
            }
        cursor.advance()
        return DirRel(sense, expressionParser.parseExpr())
    }
}
