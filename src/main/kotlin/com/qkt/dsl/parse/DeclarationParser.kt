package com.qkt.dsl.parse

import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.ParamDecl
import com.qkt.dsl.ast.SequenceDecl
import com.qkt.dsl.ast.SequenceStageDecl

/**
 * Parses the named declarations a strategy makes before its rules: `LET` expressions, `PARAM`
 * constants, and `SEQUENCE` stage machines.
 */
internal class DeclarationParser(
    private val cursor: TokenCursor,
    private val literalParser: LiteralParser,
    private val expressionParser: ExpressionParser,
) {
    fun parseLet(): List<LetDecl> {
        val out = mutableListOf<LetDecl>()
        cursor.expect(TokenKind.LET, "expected LET")
        do {
            val name = cursor.expect(TokenKind.IDENT, "expected let name").lexeme
            cursor.expect(TokenKind.EQ, "expected '=' after let name")
            val expr = expressionParser.parseExpr()
            out.add(LetDecl(name, expr))
        } while (cursor.match(TokenKind.COMMA))
        return out
    }

    /** Parses one `PARAM name = literal`; [declared] are the names already taken by earlier lines. */
    fun parseParams(declared: Set<String> = emptySet()): List<ParamDecl> {
        val out = mutableListOf<ParamDecl>()
        cursor.expect(TokenKind.PARAM, "expected PARAM")
        val nameTok = cursor.expect(TokenKind.IDENT, "expected param name")
        if (nameTok.lexeme in declared) cursor.errorAt(nameTok, "Duplicate PARAM '${nameTok.lexeme}'")
        cursor.expect(TokenKind.EQ, "expected '=' after param name")
        out.add(ParamDecl(nameTok.lexeme, literalParser.parseLiteral()))
        return out
    }

    fun parseSequence(): SequenceDecl {
        cursor.expect(TokenKind.SEQUENCE, "expected SEQUENCE")
        val nameTok = cursor.expect(TokenKind.IDENT, "expected sequence name after SEQUENCE")
        val name = nameTok.lexeme
        cursor.expect(TokenKind.ON, "expected ON after SEQUENCE name")
        val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after SEQUENCE ON").lexeme
        cursor.expect(TokenKind.LBRACE, "expected '{' to open SEQUENCE block")
        val stages = mutableListOf<SequenceStageDecl>()
        while (cursor.peek().kind != TokenKind.RBRACE && cursor.peek().kind != TokenKind.EOF) {
            cursor.expect(TokenKind.STAGE, "expected STAGE in SEQUENCE block")
            val stageName = cursor.expect(TokenKind.IDENT, "expected stage name after STAGE").lexeme
            val within = if (cursor.match(TokenKind.WITHIN)) literalParser.parseDuration() else null
            cursor.expect(TokenKind.COLON, "expected ':' after SEQUENCE stage header")
            stages += SequenceStageDecl(stageName, within, expressionParser.parseExpr())
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close SEQUENCE block")
        if (stages.size !in 2..8) {
            cursor.errorAt(nameTok, "SEQUENCE '$name' must declare 2-8 stages, got ${stages.size}")
        }
        return SequenceDecl(name, stream, stages)
    }
}
