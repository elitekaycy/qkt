package com.qkt.dsl.parse

import com.qkt.common.Money
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import java.math.BigDecimal

/**
 * Parses a `SIZING` clause into a [SizingAst]: a fixed quantity, a notional (`USD`), a percent of
 * equity or balance, a risk budget (`RISK`, `N PCT RISK`, optionally `OF BOOK`), or the full
 * current position.
 */
internal class SizingParser(
    private val cursor: TokenCursor,
    private val expressionParser: ExpressionParser,
) {
    internal fun parseSizing(): SizingAst {
        val k = cursor.peek().kind
        return when (k) {
            TokenKind.RISK -> {
                cursor.advance()
                if (cursor.match(TokenKind.DOLLAR)) {
                    SizeRiskAbs(expressionParser.parseExpr())
                } else {
                    riskFracWithOptionalBookBasis(expressionParser.parseExpr())
                }
            }
            TokenKind.POSITION -> {
                // SIZING POSITION.<alias>
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after POSITION")
                val alias = cursor.expectFieldName().lexeme
                SizePositionFull(alias)
            }
            else -> {
                val e = expressionParser.parseExpr()
                when (cursor.peek().kind) {
                    TokenKind.USD -> {
                        cursor.advance()
                        SizeNotional(e)
                    }
                    TokenKind.PCT -> {
                        cursor.advance()
                        if (cursor.peek().kind == TokenKind.OF) {
                            cursor.advance()
                            parsePercentOf(e)
                        } else {
                            cursor.expect(TokenKind.RISK, "expected RISK or OF after PCT in SIZING")
                            require(e is NumLit) {
                                "SIZING N PCT RISK requires a numeric literal for N, got non-literal expression"
                            }
                            val pct = e.value
                            require(pct.signum() > 0) {
                                "SIZING N PCT RISK requires N > 0, got $pct"
                            }
                            riskFracWithOptionalBookBasis(percentToFraction(e))
                        }
                    }
                    TokenKind.PERCENT -> {
                        cursor.advance()
                        cursor.expect(TokenKind.OF, "expected OF after %")
                        parsePercentOf(e)
                    }
                    else -> SizeQty(e)
                }
            }
        }
    }

    /**
     * `RISK <frac>` sizes off the strategy's own equity; an optional `OF BOOK` suffix
     * re-bases it on the whole portfolio book (CAPITAL + realized PnL of every child).
     * BOOK is matched as a contextual identifier, not a reserved keyword, so existing
     * strategies may keep `book` as an alias or param name.
     */
    private fun riskFracWithOptionalBookBasis(frac: ExprAst): SizingAst {
        if (cursor.peek().kind != TokenKind.OF) return SizeRiskFrac(frac)
        cursor.advance()
        val basis = cursor.advance()
        require(basis.lexeme.uppercase() == "BOOK") {
            "expected BOOK after OF in SIZING RISK, got '${basis.lexeme}'"
        }
        return SizeRiskFracOfBook(frac)
    }

    private fun parsePercentOf(e: ExprAst): SizingAst =
        when (cursor.peek().kind) {
            TokenKind.EQUITY -> {
                cursor.advance()
                SizePctEquity(percentToFraction(e))
            }
            TokenKind.BALANCE -> {
                cursor.advance()
                SizePctBalance(percentToFraction(e))
            }
            else -> cursor.error("expected EQUITY or BALANCE after % OF, got '${cursor.peek().lexeme}'")
        }

    /**
     * Normalizes the number before `%`/`PCT` from a percentage to a fraction — the
     * single place the "N means N percent" sizing convention is applied, so every
     * percent form agrees. e.g. `SIZING 2 % OF EQUITY` compiles with frac 0.02.
     */
    private fun percentToFraction(e: ExprAst): ExprAst =
        if (e is NumLit) {
            NumLit(e.value.divide(BigDecimal(100), Money.CONTEXT))
        } else {
            BinaryOp(BinOp.DIV, e, NumLit(BigDecimal(100)))
        }
}
