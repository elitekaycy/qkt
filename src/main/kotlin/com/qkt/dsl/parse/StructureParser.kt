package com.qkt.dsl.parse

import com.qkt.dsl.ast.OpenStructure
import com.qkt.dsl.ast.StructureLegAst
import com.qkt.dsl.ast.StructureLegRight
import com.qkt.dsl.ast.StructureLegSide
import java.math.BigDecimal

/**
 * Parses `OPEN <alias> = OPTIONS ON <VENUE>:<ROOT> { leg (, leg)* } SIZING <sizing>`, where a leg is
 * `BUY|SELL CALL|PUT DELTA <0..1> (DTE <n> TO <m> | SAME EXPIRY)` and the first leg names a DTE window.
 * `OPTIONS`, `CALL`, `PUT`, `DELTA`, `DTE`, `SAME` and `EXPIRY` are read as words in this position
 * only (in any case, like the keywords around them), so none of them becomes a reserved keyword elsewhere.
 */
internal class StructureParser(
    private val cursor: TokenCursor,
    private val sizingParser: SizingParser,
) {
    fun parseOpenStructure(): OpenStructure {
        cursor.expect(TokenKind.OPEN, "expected OPEN")
        val alias = cursor.expect(TokenKind.IDENT, "expected a structure alias after OPEN").lexeme
        cursor.expect(TokenKind.EQ, "expected '=' after the structure alias")
        word("OPTIONS")
        cursor.expect(TokenKind.ON, "expected ON after OPTIONS")
        val venue = cursor.expect(TokenKind.IDENT, "expected <VENUE>:<ROOT> after OPTIONS ON").lexeme
        cursor.expect(TokenKind.COLON, "expected ':' between venue and root")
        val root = "$venue:${cursor.expect(TokenKind.IDENT, "expected the option root after ':'").lexeme}"
        cursor.expect(TokenKind.LBRACE, "expected '{' to open the structure's legs")
        val legs = mutableListOf(leg())
        while (cursor.match(TokenKind.COMMA)) legs += leg()
        cursor.expect(TokenKind.RBRACE, "expected '}' after the structure's legs")
        if (legs.first().minDays ==
            null
        ) {
            cursor.error("the first leg of $alias must name a DTE window; SAME EXPIRY refers to it")
        }
        cursor.expect(TokenKind.SIZING, "a structure needs SIZING (contracts per leg, or N PCT RISK)")
        return OpenStructure(alias, root, legs, sizingParser.parseSizing())
    }

    private fun leg(): StructureLegAst {
        val side =
            when {
                cursor.match(TokenKind.BUY) -> StructureLegSide.BUY
                cursor.match(TokenKind.SELL) -> StructureLegSide.SELL
                else -> cursor.error("expected BUY or SELL to start a leg")
            }
        val right =
            when (cursor.advance().lexeme.uppercase()) {
                "CALL" -> StructureLegRight.CALL
                "PUT" -> StructureLegRight.PUT
                else -> cursor.error("expected CALL or PUT after $side")
            }
        word("DELTA")
        val delta = number()
        if (delta.signum() <= 0 || delta >= BigDecimal.ONE) cursor.error("a leg's DELTA is between 0 and 1, got $delta")
        if (cursor.peek().lexeme.equals("SAME", ignoreCase = true)) {
            word("SAME")
            word("EXPIRY")
            return StructureLegAst(side, right, delta, null, null)
        }
        word("DTE")
        val min = whole()
        cursor.expect(TokenKind.TO, "expected TO in the DTE window")
        val max = whole()
        if (min > max) cursor.error("DTE window $min TO $max is empty")
        return StructureLegAst(side, right, delta, min, max)
    }

    private fun word(expected: String) {
        val token = cursor.peek()
        if (!token.lexeme.equals(expected, ignoreCase = true)) cursor.error("expected $expected, got '${token.lexeme}'")
        cursor.advance()
    }

    private fun number(): BigDecimal =
        cursor.expect(TokenKind.NUMBER, "expected a number").lexeme.toBigDecimalOrNull()
            ?: cursor.error("expected a number")

    private fun whole(): Int {
        val lexeme = cursor.expect(TokenKind.NUMBER, "expected a whole number of days").lexeme
        return lexeme.toIntOrNull()?.takeIf { it >= 0 }
            ?: cursor.error("expected a whole number of days, got '$lexeme'")
    }
}
