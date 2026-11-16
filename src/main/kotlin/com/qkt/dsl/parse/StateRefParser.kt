package com.qkt.dsl.parse

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.ExitRef
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.TradesRef

/**
 * Parses the references to live trading state an expression can read: the last exit
 * (`EXIT.price`), account and trade statistics (`ACCOUNT.*`, `STREAK.*`, `TRADES.*`, `COOLDOWN.*`),
 * a sequence's stage (`SEQUENCE.<name>.*`), and positions and open orders on a stream
 * (`POSITION.<alias>.<accessor>`).
 */
internal class StateRefParser(
    private val cursor: TokenCursor,
) {
    /** Parses the state reference that starts at [t], the current token. */
    fun parseStateRef(t: Token): ExprAst =
        when (t.kind) {
            TokenKind.EXIT -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after EXIT")
                val field = cursor.expectFieldName().lexeme.uppercase()
                ExitRef(DslVocabulary.exitFields[field.lowercase()] ?: cursor.error("unknown EXIT field '$field'"))
            }
            TokenKind.ACCOUNT -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after ACCOUNT")
                AccountRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.STREAK -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after STREAK")
                StreakRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.TRADES -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after TRADES")
                TradesRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.COOLDOWN -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after COOLDOWN")
                CooldownRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.SEQUENCE -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after SEQUENCE")
                val sequenceName = cursor.expectFieldName().lexeme
                cursor.expect(TokenKind.DOT, "expected '.' after SEQUENCE name")
                val first = cursor.expectFieldName().lexeme
                if (first in DslVocabulary.sequenceMembers) {
                    SequenceAccessor(sequenceName, null, first)
                } else {
                    cursor.expect(TokenKind.DOT, "expected '.' after SEQUENCE stage name")
                    SequenceAccessor(sequenceName, first, cursor.expectFieldName().lexeme)
                }
            }
            TokenKind.POSITION -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after POSITION")
                val streamAlias = cursor.expectFieldName().lexeme
                if (cursor.peek().kind == TokenKind.DOT) {
                    cursor.advance()
                    val accessor = cursor.expectFieldName().lexeme
                    val accessors = DslVocabulary.positionAccessors
                    if (accessor !in accessors) {
                        cursor.errors += ParseError(t.line, t.col, "unknown POSITION accessor: $accessor")
                    }
                    accessors[accessor]?.let { StateAccessor(it, streamAlias) } ?: PositionRef(streamAlias)
                } else {
                    PositionRef(streamAlias)
                }
            }
            TokenKind.POSITION_AVG_PRICE -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after POSITION_AVG_PRICE")
                StateAccessor(StateSource.POSITION_AVG_PRICE, cursor.expectFieldName().lexeme)
            }
            TokenKind.OPEN_ORDERS -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after OPEN_ORDERS")
                StateAccessor(StateSource.OPEN_ORDERS, cursor.expectFieldName().lexeme)
            }
            else -> cursor.error("expected expression, got '${t.lexeme}'")
        }
}
