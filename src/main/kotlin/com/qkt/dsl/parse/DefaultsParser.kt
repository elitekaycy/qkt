package com.qkt.dsl.parse

import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.TifAst

/**
 * Parses the `DEFAULTS { ... }` block: the sizing, order type, TIF, stop loss, take profit and
 * trailing stop an action inherits when it does not set its own.
 */
internal class DefaultsParser(
    private val cursor: TokenCursor,
    private val sizingParser: SizingParser,
    private val orderTypeParser: OrderTypeParser,
    private val bracketParser: BracketParser,
) {
    internal fun parseDefaults(): DefaultsBlock {
        cursor.expect(TokenKind.DEFAULTS, "expected DEFAULTS")
        cursor.expect(TokenKind.LBRACE, "expected '{' after DEFAULTS")
        var sizing: SizingAst? = null
        var orderType: OrderTypeAst? = null
        var tif: TifAst? = null
        var stopLoss: ChildPriceAst? = null
        var takeProfit: ChildPriceAst? = null
        var trailing: OrderTypeAst? = null
        while (cursor.peek().kind != TokenKind.RBRACE && cursor.peek().kind != TokenKind.EOF) {
            when (cursor.peek().kind) {
                TokenKind.SIZING -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after SIZING in DEFAULTS")
                    sizing = sizingParser.parseSizing()
                }
                TokenKind.STOP_LOSS -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after STOP_LOSS in DEFAULTS")
                    stopLoss = bracketParser.parseChildPrice()
                }
                TokenKind.TAKE_PROFIT -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after TAKE_PROFIT in DEFAULTS")
                    takeProfit = bracketParser.parseChildPrice()
                }
                TokenKind.TIF -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after TIF in DEFAULTS")
                    tif = orderTypeParser.parseTif()
                }
                TokenKind.ORDER_TYPE -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after ORDER_TYPE in DEFAULTS")
                    orderType = orderTypeParser.parseOrderType()
                }
                TokenKind.TRAILING -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after TRAILING in DEFAULTS")
                    trailing = orderTypeParser.parseOrderType()
                }
                else -> cursor.error("expected DEFAULTS clause keyword, got '${cursor.peek().lexeme}'")
            }
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close DEFAULTS")
        return DefaultsBlock(sizing, orderType, tif, stopLoss, takeProfit, trailing)
    }
}
