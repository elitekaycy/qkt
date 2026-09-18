package com.qkt.dsl.parse

import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp

/**
 * Parses an expression by precedence climbing, loosest binding first: `OR`, `AND`, `NOT`, the
 * comparison family (`>`, `BETWEEN`, `IN`, `CROSSES`, `IS NULL`), `+ -`, `* /`, unary minus.
 * Everything below unary minus is a primary, which [PrimaryParser] reads.
 */
internal class ExpressionParser(
    private val cursor: TokenCursor,
    private val scope: ParseScope,
    private val literalParser: LiteralParser,
) {
    private val primaryParser = PrimaryParser(cursor, scope, literalParser, this)

    fun parseExpr(): ExprAst = parseOrExpr()

    private fun parseOrExpr(): ExprAst {
        var lhs = parseAndExpr()
        while (cursor.peek().kind == TokenKind.OR) {
            cursor.advance()
            val rhs = parseAndExpr()
            lhs = BinaryOp(BinOp.OR, lhs, rhs)
        }
        return lhs
    }

    private fun parseAndExpr(): ExprAst {
        var lhs = parseNotExpr()
        while (cursor.peek().kind == TokenKind.AND) {
            cursor.advance()
            val rhs = parseNotExpr()
            lhs = BinaryOp(BinOp.AND, lhs, rhs)
        }
        return lhs
    }

    private fun parseNotExpr(): ExprAst {
        if (cursor.match(TokenKind.NOT)) return UnaryOp(UnOp.NOT, parseNotExpr())
        return parseCmpExpr()
    }

    private fun parseCmpExpr(): ExprAst {
        var lhs = parseAddExpr()
        while (true) {
            val k = cursor.peek().kind
            val op =
                when (k) {
                    TokenKind.GT -> Cmp.GT
                    TokenKind.LT -> Cmp.LT
                    TokenKind.GE -> Cmp.GE
                    TokenKind.LE -> Cmp.LE
                    TokenKind.EQEQ -> Cmp.EQ
                    TokenKind.EQ -> Cmp.EQ
                    TokenKind.NEQ -> Cmp.NE
                    else -> null
                }
            if (op != null) {
                cursor.advance()
                val rhs = parseAddExpr()
                lhs = CmpOp(op, lhs, rhs)
                continue
            }
            when (k) {
                TokenKind.BETWEEN -> {
                    cursor.advance()
                    val lo = parseAddExpr()
                    cursor.expect(TokenKind.AND, "expected AND between BETWEEN bounds")
                    val hi = parseAddExpr()
                    lhs = Between(lhs, lo, hi)
                }
                TokenKind.IN -> {
                    cursor.advance()
                    cursor.expect(TokenKind.LBRACKET, "expected '[' after IN")
                    val members = mutableListOf<ExprAst>()
                    if (cursor.peek().kind != TokenKind.RBRACKET) {
                        members.add(parseExpr())
                        while (cursor.match(TokenKind.COMMA)) members.add(parseExpr())
                    }
                    cursor.expect(TokenKind.RBRACKET, "expected ']' to close IN list")
                    lhs = InList(lhs, members)
                }
                TokenKind.CROSSES -> {
                    cursor.advance()
                    val dir =
                        when (cursor.peek().kind) {
                            TokenKind.ABOVE -> {
                                cursor.advance()
                                CrossDir.ABOVE
                            }
                            TokenKind.BELOW -> {
                                cursor.advance()
                                CrossDir.BELOW
                            }
                            else -> cursor.error("expected ABOVE or BELOW after CROSSES, got '${cursor.peek().lexeme}'")
                        }
                    val rhs = parseAddExpr()
                    lhs = Crosses(dir, lhs, rhs)
                }
                TokenKind.IS -> {
                    cursor.advance()
                    val negated = cursor.match(TokenKind.NOT)
                    cursor.expect(TokenKind.NULL, "expected NULL after IS${if (negated) " NOT" else ""}")
                    lhs = IsNull(lhs, negated)
                }
                else -> return lhs
            }
        }
    }

    private fun parseAddExpr(): ExprAst {
        var lhs = parseMulExpr()
        while (cursor.peek().kind == TokenKind.PLUS || cursor.peek().kind == TokenKind.MINUS) {
            val op = if (cursor.advance().kind == TokenKind.PLUS) BinOp.ADD else BinOp.SUB
            val rhs = parseMulExpr()
            lhs = BinaryOp(op, lhs, rhs)
        }
        return lhs
    }

    private fun parseMulExpr(): ExprAst {
        var lhs = parseUnaryExpr()
        while (cursor.peek().kind == TokenKind.STAR || cursor.peek().kind == TokenKind.SLASH) {
            val op = if (cursor.advance().kind == TokenKind.STAR) BinOp.MUL else BinOp.DIV
            val rhs = parseUnaryExpr()
            lhs = BinaryOp(op, lhs, rhs)
        }
        return lhs
    }

    private fun parseUnaryExpr(): ExprAst {
        if (cursor.match(TokenKind.MINUS)) return UnaryOp(UnOp.NEG, parseUnaryExpr())
        return primaryParser.parsePrimary()
    }
}
