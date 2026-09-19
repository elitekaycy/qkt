package com.qkt.dsl.parse

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit

/**
 * Parses the clauses that add to a position after the first fill: `STACK <n> SPACING ...`, an
 * explicit `STACK [ ... ]` layer list, and `STACK_AT MFE|MAE ...` triggers. While a layer's
 * trigger price is read, bare `entry` refers to the parent entry price.
 */
internal class StackClauseParser(
    private val cursor: TokenCursor,
    private val scope: ParseScope,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
    private val sizingParser: SizingParser,
    private val orderTypeParser: OrderTypeParser,
    private val bracketParser: BracketParser,
) {
    internal fun parseStackClause(): StackAst {
        // STACK <count> SPACING <expr> [ABOVE|BELOW] [WITHIN <duration>]
        // STACK [ <layers> ] [WITHIN <duration>]   (added in Task 5)
        return if (cursor.peek().kind == TokenKind.LBRACKET) {
            parseStackLayers()
        } else {
            parseStackSpacing()
        }
    }

    internal fun parseStackSpacing(): StackSpacing {
        val countTok = cursor.expect(TokenKind.NUMBER, "expected count after STACK")
        val count =
            countTok.lexeme.toIntOrNull()
                ?: cursor.error("STACK count must be a positive integer, got '${countTok.lexeme}'")
        if (count < 1) cursor.error("STACK count must be >= 1, got $count")
        cursor.expect(TokenKind.SPACING, "expected SPACING after STACK count")
        val spacing = expressionParser.parseExpr()
        val direction =
            when (cursor.peek().kind) {
                TokenKind.ABOVE -> {
                    cursor.advance()
                    StackDirection.ABOVE
                }
                TokenKind.BELOW -> {
                    cursor.advance()
                    StackDirection.BELOW
                }
                else -> StackDirection.TRADE_DIRECTION
            }
        val within = if (cursor.peek().kind == TokenKind.WITHIN) literalParser.parseWithin() else null
        return StackSpacing(count, spacing, direction, within)
    }

    internal fun parseStackLayers(): StackLayers {
        cursor.expect(TokenKind.LBRACKET, "expected '[' to open layer list")
        val layers = mutableListOf<StackLayer>()
        if (cursor.peek().kind == TokenKind.RBRACKET) {
            cursor.error("STACK layer list must not be empty")
        }
        layers.add(parseLayer(isFirst = true))
        while (cursor.peek().kind == TokenKind.COMMA) {
            cursor.advance()
            if (cursor.peek().kind == TokenKind.RBRACKET) break
            layers.add(parseLayer(isFirst = false))
        }
        cursor.expect(TokenKind.RBRACKET, "expected ']' to close layer list")
        val within = if (cursor.peek().kind == TokenKind.WITHIN) literalParser.parseWithin() else null
        return StackLayers(layers, within)
    }

    internal fun parseLayer(isFirst: Boolean): StackLayer {
        val sizing = sizingParser.parseSizing()
        scope.inStackLayerAt = true
        try {
            val orderType: OrderTypeAst? =
                when (cursor.peek().kind) {
                    TokenKind.MARKET, TokenKind.LIMIT, TokenKind.STOP -> orderTypeParser.parseOrderType()
                    else -> null
                }
            val priceFromOrderType: ExprAst? =
                when (orderType) {
                    is Limit -> orderType.price
                    is Stop -> orderType.price
                    is StopLimit -> orderType.stopPrice
                    else -> null
                }
            val explicitAt: ExprAst? =
                if (cursor.peek().kind == TokenKind.AT) {
                    if (priceFromOrderType != null) {
                        cursor.error(
                            "STACK layer with LIMIT/STOP/STOPLIMIT cannot have a separate AT clause; " +
                                "the order type's price is the trigger",
                        )
                    }
                    cursor.advance()
                    expressionParser.parseExpr()
                } else {
                    null
                }
            val at: ExprAst? = priceFromOrderType ?: explicitAt
            if (!isFirst && at == null) {
                cursor.error("STACK layers after the first must have a trigger (via AT or LIMIT/STOP price)")
            }
            return StackLayer(sizing, orderType, at)
        } finally {
            scope.inStackLayerAt = false
        }
    }

    /**
     * Phase 27: `STACK_AT MFE >= <expr> WITHIN <duration> SIZING <sizing> BRACKET { ... }`.
     * Phase 38: `STACK_AT MAE >= <expr> RECOVER <expr> WITHIN <duration> ...`.
     *
     * The clause attaches to its parent BUY/SELL action. The stack engine fires the stack
     * when the parent leg's MFE crosses the threshold within the duration window.
     */
    fun parseStackAtClause(): StackAtClause {
        cursor.expect(TokenKind.STACK_AT, "expected STACK_AT")
        val trigger = cursor.peek().kind
        when (trigger) {
            TokenKind.MFE, TokenKind.MAE -> cursor.advance()
            else -> {
                cursor.errors += ParseError(cursor.peek().line, cursor.peek().col, "expected MFE or MAE after STACK_AT")
                cursor.advance()
            }
        }
        cursor.expect(TokenKind.GE, "expected '>=' after MFE/MAE in STACK_AT")
        val threshold = expressionParser.parseExpr()
        val recoverDistance =
            if (trigger == TokenKind.MAE) {
                cursor.expect(TokenKind.RECOVER, "expected RECOVER after MAE threshold in STACK_AT")
                expressionParser.parseExpr()
            } else {
                null
            }
        cursor.expect(TokenKind.WITHIN, "expected WITHIN after STACK_AT threshold")
        val duration = literalParser.parseDuration()
        cursor.expect(TokenKind.SIZING, "expected SIZING in STACK_AT clause")
        val sizing = sizingParser.parseSizing()
        cursor.expect(TokenKind.BRACKET, "expected BRACKET in STACK_AT clause")
        val bracket = bracketParser.parseBracket()
        return StackAtClause(
            mfeThreshold = threshold,
            withinDuration = duration,
            sizing = sizing,
            bracket = bracket,
            maeRecoverDistance = recoverDistance,
        )
    }
}
