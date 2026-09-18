package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell

/**
 * Parses one action after `THEN`: `BUY`/`SELL` with their options, `CLOSE`, `CLOSE_ALL`/`FLATTEN`,
 * `RESIZE`, `CANCEL`, `CANCEL_ALL`, `LOG`, `OCO_ENTRY` and `LATCH`. BUY/SELL options and LATCH blocks
 * are read by [ActionOptsParser] and [LatchParser].
 */
internal class ActionParser(
    private val cursor: TokenCursor,
    private val scope: ParseScope,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
    private val sizingParser: SizingParser,
    private val orderTypeParser: OrderTypeParser,
    private val bracketParser: BracketParser,
) {
    private val latchParser = LatchParser(cursor, literalParser, expressionParser, sizingParser, orderTypeParser)
    private val actionOptsParser =
        ActionOptsParser(
            cursor,
            scope,
            literalParser,
            expressionParser,
            sizingParser,
            orderTypeParser,
            bracketParser,
            this,
        )

    internal fun parseAction(): ActionAst =
        when (cursor.peek().kind) {
            TokenKind.BUY -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after BUY").lexeme
                Buy(stream, actionOptsParser.parseActionOpts())
            }
            TokenKind.SELL -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after SELL").lexeme
                Sell(stream, actionOptsParser.parseActionOpts())
            }
            TokenKind.CLOSE -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after CLOSE").lexeme
                Close(stream)
            }
            TokenKind.CLOSE_ALL -> {
                cursor.advance()
                CloseAll
            }
            TokenKind.FLATTEN -> {
                cursor.advance()
                CloseAll
            }
            TokenKind.RESIZE -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after RESIZE").lexeme
                cursor.expect(TokenKind.TO, "expected TO after RESIZE stream")
                val target = sizingParser.parseSizing()
                val minStep = if (cursor.match(TokenKind.MIN_STEP)) expressionParser.parseExpr() else null
                Resize(stream, target, minStep)
            }
            TokenKind.CANCEL -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after CANCEL").lexeme
                Cancel(stream)
            }
            TokenKind.CANCEL_ALL -> {
                cursor.advance()
                CancelAll
            }
            TokenKind.LOG -> parseLogAction()
            TokenKind.OCO_ENTRY -> parseOcoEntry()
            TokenKind.LATCH -> latchParser.parseLatch()
            else -> cursor.error("expected action keyword, got '${cursor.peek().lexeme}'")
        }

    private fun parseOcoEntry(): ActionAst {
        cursor.expect(TokenKind.OCO_ENTRY, "expected OCO_ENTRY")
        cursor.expect(TokenKind.LBRACE, "expected '{' after OCO_ENTRY")
        val leg1 = parseAction()
        if (leg1 !is Buy && leg1 !is Sell) {
            cursor.error("OCO_ENTRY legs must be BUY or SELL, got ${leg1::class.simpleName}")
        }
        cursor.expect(TokenKind.COMMA, "expected ',' between OCO_ENTRY legs")
        val leg2 = parseAction()
        if (leg2 !is Buy && leg2 !is Sell) {
            cursor.error("OCO_ENTRY legs must be BUY or SELL, got ${leg2::class.simpleName}")
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close OCO_ENTRY (exactly two legs)")
        return OcoEntry(leg1, leg2)
    }

    private fun parseLogAction(): Log {
        cursor.expect(TokenKind.LOG, "expected LOG")
        val level =
            when (cursor.peek().kind) {
                TokenKind.WARN -> {
                    cursor.advance()
                    LogLevel.WARN
                }
                TokenKind.ERROR -> {
                    cursor.advance()
                    LogLevel.ERROR
                }
                TokenKind.DEBUG -> {
                    cursor.advance()
                    LogLevel.DEBUG
                }
                else -> LogLevel.INFO
            }
        val message = cursor.expect(TokenKind.STRING, "expected string literal after LOG").lexeme
        val fields = linkedMapOf<String, ExprAst>()
        while (cursor.peek().kind == TokenKind.IDENT && cursor.peekAtOrNull(1)?.kind == TokenKind.EQ) {
            val name = cursor.expect(TokenKind.IDENT, "expected field name").lexeme
            cursor.expect(TokenKind.EQ, "expected '='")
            val expr = expressionParser.parseExpr()
            if (fields.containsKey(name)) {
                cursor.error("duplicate LOG field '$name'")
            }
            fields[name] = expr
        }
        val placeholders = LOG_PLACEHOLDER_REGEX.findAll(message).map { it.groupValues[1] }.toSet()
        val unmatched = placeholders - fields.keys
        if (unmatched.isNotEmpty()) {
            cursor.error("LOG placeholder(s) without matching field: ${unmatched.joinToString()}")
        }
        return Log(level, message, fields)
    }

    companion object {
        private val LOG_PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")
    }
}
