package com.qkt.dsl.parse

import com.qkt.dsl.ast.AllocateBlock
import com.qkt.dsl.ast.PortfolioAllocationMethod
import java.math.BigDecimal

/**
 * Parses a portfolio's `ALLOCATE` block: the allocation method, the optional rebalance interval,
 * and the per-regime weight of each child alias (or `CASH`).
 */
internal class AllocationParser(
    private val cursor: TokenCursor,
    private val literalParser: LiteralParser,
) {
    internal fun parseAllocate(): AllocateBlock {
        cursor.expect(TokenKind.ALLOCATE, "expected ALLOCATE")
        cursor.expect(TokenKind.METHOD, "expected METHOD after ALLOCATE")
        val method = parseAllocationMethod()
        val rebalance =
            if (cursor.peek().kind == TokenKind.REBALANCE) {
                cursor.advance()
                cursor.expect(TokenKind.EVERY, "expected EVERY after REBALANCE")
                literalParser.parseDuration()
            } else {
                null
            }
        val entries = parseAllocateEntries()
        return AllocateBlock(method, rebalance?.millis, entries)
    }

    private fun parseAllocationMethod(): PortfolioAllocationMethod {
        val tok = cursor.expect(TokenKind.IDENT, "expected allocation method")
        return when (tok.lexeme.uppercase()) {
            "REGIME_WEIGHTED" -> PortfolioAllocationMethod.REGIME_WEIGHTED
            else -> cursor.error("unknown allocation method '${tok.lexeme}'")
        }
    }

    private fun parseAllocateEntries(): Map<String, Map<String, BigDecimal>> {
        val out = LinkedHashMap<String, Map<String, BigDecimal>>()
        while (cursor.peek().kind == TokenKind.IDENT) {
            val regimeName = cursor.expect(TokenKind.IDENT, "expected regime name").lexeme
            cursor.expect(TokenKind.ARROW, "expected '->' after regime name")
            val entries = LinkedHashMap<String, BigDecimal>()
            do {
                val aliasTok =
                    when (cursor.peek().kind) {
                        TokenKind.IDENT -> cursor.expect(TokenKind.IDENT, "expected alias")
                        TokenKind.CASH -> {
                            cursor.advance()
                            Token(TokenKind.IDENT, "cash", -1, -1)
                        }
                        else -> cursor.error("expected alias or CASH in allocate entry")
                    }
                val weight =
                    cursor
                        .expect(
                            TokenKind.NUMBER,
                            "expected weight for alias '${aliasTok.lexeme}'",
                        ).lexeme
                        .toBigDecimalOrNull()
                        ?: cursor.error("weight must be a number, got '${cursor.peek().lexeme}'")
                entries[aliasTok.lexeme] = weight
            } while (cursor.match(TokenKind.COMMA))
            out[regimeName] = entries
        }
        return out
    }
}
