package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExitHooksAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.TifAst

/**
 * Parses the option clauses that may follow `BUY`/`SELL <stream>` in any order: sizing, order
 * type, TIF, bracket, OCO, stacking, `TIMES`, `EXIT AFTER`, and the
 * `ON_FILL`/`ON_STOP`/`ON_TP`/`ON_CLOSE` child blocks. Child blocks re-enter [ActionParser], with [ParseScope] set so the child reads `entry`
 * and relative prices correctly.
 */
internal class ActionOptsParser(
    private val cursor: TokenCursor,
    private val scope: ParseScope,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
    private val sizingParser: SizingParser,
    private val orderTypeParser: OrderTypeParser,
    private val bracketParser: BracketParser,
    private val actionParser: ActionParser,
) {
    private val stackClauseParser =
        StackClauseParser(cursor, scope, literalParser, expressionParser, sizingParser, orderTypeParser, bracketParser)

    fun parseActionOpts(): ActionOpts {
        var sizing: SizingAst? = null
        var orderType: OrderTypeAst? = null
        var tif: TifAst? = null
        var bracket: BracketAst? = null
        var oco: OcoAst? = null
        var stack: StackAst? = null
        var stackAts: List<StackAtClause> = emptyList()
        var onFill: List<ActionAst> = emptyList()
        var onStop: List<ActionAst> = emptyList()
        var onTakeProfit: List<ActionAst> = emptyList()
        var onClose: List<ActionAst> = emptyList()
        var times: ExprAst? = null
        var exitAfter: DurationAst? = null
        loop@ while (true) {
            when (cursor.peek().kind) {
                TokenKind.SIZING -> {
                    cursor.advance()
                    sizing = sizingParser.parseSizing()
                }
                TokenKind.TIMES -> {
                    if (times != null) cursor.error("duplicate TIMES clause")
                    cursor.advance()
                    times = expressionParser.parseExpr()
                }
                TokenKind.EXIT -> {
                    if (exitAfter != null) cursor.error("duplicate EXIT AFTER clause")
                    exitAfter = parseExitAfter()
                }
                TokenKind.ORDER_TYPE -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after ORDER_TYPE")
                    orderType = orderTypeParser.parseOrderType()
                }
                TokenKind.TIF -> {
                    cursor.advance()
                    tif = orderTypeParser.parseTif()
                }
                TokenKind.BRACKET -> {
                    cursor.advance()
                    bracket = bracketParser.parseBracket()
                }
                TokenKind.OCO -> {
                    cursor.advance()
                    oco = bracketParser.parseOco()
                }
                TokenKind.STACK -> {
                    cursor.advance()
                    stack = stackClauseParser.parseStackClause()
                }
                TokenKind.STACK_AT -> {
                    stackAts += stackClauseParser.parseStackAtClause()
                }
                TokenKind.ON_FILL -> {
                    cursor.advance()
                    onFill = parseOnFill()
                }
                TokenKind.ON_STOP -> {
                    if (onStop.isNotEmpty()) cursor.error("duplicate ON_STOP clause")
                    cursor.advance()
                    onStop = parseExitHook("ON_STOP")
                }
                TokenKind.ON_TP -> {
                    if (onTakeProfit.isNotEmpty()) cursor.error("duplicate ON_TP clause")
                    cursor.advance()
                    onTakeProfit = parseExitHook("ON_TP")
                }
                TokenKind.ON_CLOSE -> {
                    if (onClose.isNotEmpty()) cursor.error("duplicate ON_CLOSE clause")
                    cursor.advance()
                    onClose = parseExitHook("ON_CLOSE")
                }
                else -> break@loop
            }
        }
        val finalStack = stack
        if (sizing != null && finalStack is StackLayers) {
            cursor.error(
                "STACK layer-list cannot be combined with outer SIZING; specify size on each layer or remove the layer list",
            )
        }
        // orderType stays null here so DEFAULTS ORDER_TYPE can fill it during the
        // defaults merge; ActionCompiler applies the Market fallback after the merge.
        return ActionOpts(
            sizing,
            orderType,
            tif,
            bracket,
            oco,
            finalStack,
            stackAts,
            onFill,
            ExitHooksAst(onStop, onTakeProfit, onClose),
            times = times,
            exitAfter = exitAfter,
        )
    }

    /** `EXIT AFTER <duration>`: a literal, positive hold measured from the entry's fill. */
    private fun parseExitAfter(): DurationAst {
        cursor.expect(TokenKind.EXIT, "expected EXIT")
        cursor.expect(TokenKind.AFTER, "expected AFTER after EXIT (e.g. EXIT AFTER 4m)")
        val tok = cursor.peek()
        if (tok.kind != TokenKind.DURATION) cursor.error("EXIT AFTER needs a duration literal (e.g. 90s, 4m, 1h)")
        if (tok.lexeme
                .dropLast(1)
                .toLongOrNull()
                ?.let { it > 0 } != true
        ) {
            cursor.error("EXIT AFTER duration must be positive, got '${tok.lexeme}'")
        }
        return literalParser.parseDuration()
    }

    /**
     * Parse an OTO child block: `ON_FILL { <BUY|SELL …> [; <BUY|SELL …>]* }`.
     *
     * Each child is a normal BUY/SELL action, so it reuses the full action grammar (sizing,
     * order type). Inside the block, `entry` resolves to the parent fill price, letting a child
     * price itself relative to where the parent filled (e.g. `LIMIT AT entry - 10`).
     */
    private fun parseOnFill(): List<ActionAst> {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open ON_FILL block")
        val children = mutableListOf<ActionAst>()
        val prev = scope.inOtoChildPrice
        scope.inOtoChildPrice = true
        try {
            children.add(actionParser.parseAction())
            while (cursor.peek().kind == TokenKind.SEMICOLON) {
                cursor.advance()
                if (cursor.peek().kind == TokenKind.RBRACE) break
                children.add(actionParser.parseAction())
            }
        } finally {
            scope.inOtoChildPrice = prev
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close ON_FILL block")
        return children
    }

    /**
     * Parse an exit hook block. Children reuse BUY/SELL parsing, while the compiler
     * enforces the v1 action and nesting constraints.
     */
    private fun parseExitHook(name: String): List<ActionAst> {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open $name block")
        val children = mutableListOf<ActionAst>()
        val previous = scope.inExitHook
        scope.inExitHook = true
        try {
            if (cursor.peek().kind == TokenKind.RBRACE) cursor.error("$name block must contain at least one action")
            children.add(actionParser.parseAction())
            while (cursor.match(TokenKind.SEMICOLON)) {
                if (cursor.peek().kind == TokenKind.RBRACE) break
                children.add(actionParser.parseAction())
            }
        } finally {
            scope.inExitHook = previous
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close $name block")
        return children
    }
}
