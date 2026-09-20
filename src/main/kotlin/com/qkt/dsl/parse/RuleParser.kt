package com.qkt.dsl.parse

import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.WhenThen

/**
 * Parses the `RULES` block: `WHEN <cond> THEN <action> [; <action>]*` rules and
 * `FOR EACH x IN [a, b] DO WHEN ...` templates, which expand to one rule per alias here so the
 * compiler only ever sees plain rules.
 */
internal class RuleParser(
    private val cursor: TokenCursor,
    private val expressionParser: ExpressionParser,
    private val actionParser: ActionParser,
) {
    internal fun parseRules(): List<RuleAst> {
        cursor.expect(TokenKind.RULES, "expected RULES")
        val out = mutableListOf<RuleAst>()
        while (cursor.peek().kind != TokenKind.EOF) {
            val kind = cursor.peek().kind
            when {
                kind == TokenKind.WHEN -> cursor.tryParse { parseWhenThen() }?.let { out.add(it) }
                kind == TokenKind.FOR -> cursor.tryParse { parseForEach() }?.let { out.addAll(it) }
                kind in SECTION_KINDS -> {
                    // A section keyword after RULES is an ordering error, not a rule. Report it
                    // once and hand the token back to the caller; `requireEof` then covers the
                    // rest. Looping on it used to append the same error forever (#1131).
                    val t = cursor.peek()
                    cursor.errors.add(
                        ParseError(
                            line = t.line,
                            col = t.col,
                            message = "${t.lexeme} must come before RULES",
                        ),
                    )
                    return out
                }
                else -> {
                    cursor.tryParse {
                        cursor.error("expected WHEN or FOR EACH in RULES, got '${cursor.peek().lexeme}'")
                    }
                }
            }
        }
        return out
    }

    private fun parseWhenThen(): WhenThen {
        cursor.expect(TokenKind.WHEN, "expected WHEN")
        val cond = expressionParser.parseExpr()
        cursor.expect(TokenKind.THEN, "expected THEN after WHEN condition")
        val first = actionParser.parseAction()
        if (cursor.peek().kind != TokenKind.SEMICOLON) return WhenThen(cond, first)
        val actions = mutableListOf(first)
        while (cursor.match(TokenKind.SEMICOLON)) {
            if (!isActionStart(cursor.peek().kind)) break
            actions.add(actionParser.parseAction())
        }
        return WhenThen(cond, Block(actions))
    }

    private fun isActionStart(k: TokenKind): Boolean =
        k == TokenKind.BUY ||
            k == TokenKind.SELL ||
            k == TokenKind.CLOSE ||
            k == TokenKind.CLOSE_ALL ||
            k == TokenKind.FLATTEN ||
            k == TokenKind.RESIZE ||
            k == TokenKind.CANCEL ||
            k == TokenKind.CANCEL_ALL ||
            k == TokenKind.LOG ||
            k == TokenKind.OCO_ENTRY ||
            k == TokenKind.LATCH

    private fun parseForEach(): List<RuleAst> {
        cursor.expect(TokenKind.FOR, "expected FOR")
        cursor.expect(TokenKind.EACH, "expected EACH after FOR")
        val iterVar = cursor.expect(TokenKind.IDENT, "expected iteration variable").lexeme
        cursor.expect(TokenKind.IN, "expected IN after iteration variable")
        cursor.expect(TokenKind.LBRACKET, "expected '[' to open stream alias list")
        val aliases = mutableListOf<String>()
        if (cursor.peek().kind != TokenKind.RBRACKET) {
            aliases.add(cursor.expect(TokenKind.IDENT, "expected stream alias").lexeme)
            while (cursor.match(TokenKind.COMMA)) {
                aliases.add(cursor.expect(TokenKind.IDENT, "expected stream alias").lexeme)
            }
        }
        cursor.expect(TokenKind.RBRACKET, "expected ']' to close stream alias list")
        cursor.expect(TokenKind.DO, "expected DO after stream alias list")
        val template = parseWhenThen()
        return aliases.map { alias -> substituteIterVar(template, iterVar, alias) }
    }

    companion object {
        /** Top-level section keywords; each opens a block that must precede RULES. */
        private val SECTION_KINDS =
            setOf(
                TokenKind.DEFAULTS,
                TokenKind.SYMBOLS,
                TokenKind.LET,
                TokenKind.PARAM,
                TokenKind.RULES,
                TokenKind.SCHEDULE,
                TokenKind.SEQUENCE,
            )
    }
}
