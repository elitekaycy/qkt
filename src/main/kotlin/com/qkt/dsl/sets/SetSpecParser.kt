package com.qkt.dsl.sets

import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.parse.TokenCursor
import com.qkt.dsl.parse.TokenKind
import com.qkt.dsl.parse.venueSymbol
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.CatalogValue
import com.qkt.instrument.universe.CompareOp
import com.qkt.instrument.universe.UniverseFilter
import java.math.BigDecimal

/**
 * Parses the symbol-set forms of a `SYMBOLS` declaration (design §2): a picked list `VENUE:{A, B}`, a filtered
 * universe `VENUE:*[@<event>.<strike>] WHERE <filter> TOP <n> BY <field> [ASC|DESC] [DYNAMIC]` and a union
 * `a + b`. `WHERE`, `TOP`,
 * `GLOB`, `ASC`, `DESC` and `DYNAMIC` are contextual words here, not keywords, so no strategy's identifiers become
 * reserved.
 */
internal class SetSpecParser(
    private val cursor: TokenCursor,
) {
    /** The set after `VENUE:` when the next tokens open one (`{`, `*` or `FAMILY@<event>.*`); the venue is [venue]. */
    fun parseVenueSet(venue: String): SymbolSetSpec =
        when (cursor.peek().kind) {
            TokenKind.LBRACE -> parsePicked(venue)
            else -> parseFiltered(venue)
        }

    /** A union declaration `alias = a + b` over sets declared above it ([declared]), which must share `EVERY`. */
    fun parseUnionDecl(
        alias: String,
        declared: List<StreamDecl>,
    ): StreamDecl {
        val spec = parseUnion()
        val operands =
            spec.operands.map { name ->
                val decl = declared.firstOrNull { it.alias == name && it.broker == SET_BROKER }
                decl ?: cursor.error("union '$alias': '$name' is not a symbol set declared above it")
            }
        val timeframes = operands.map { it.timeframe }.toSet()
        if (timeframes.size != 1) {
            cursor.error("union '$alias': its sets must share EVERY, got ${timeframes.sorted().joinToString()}")
        }
        return StreamDecl(alias, SET_BROKER, spec.canonical, operands.first().timeframe)
    }

    /** A whole spec on its own (`qkt bot universe "<spec>"`): a union, or `VENUE:` followed by a set. */
    fun parseStandalone(): SymbolSetSpec {
        val spec =
            if (cursor.peekAtOrNull(1)?.kind == TokenKind.PLUS) {
                parseUnion()
            } else {
                val venue = cursor.expect(TokenKind.IDENT, "expected a venue prefix").lexeme
                cursor.expect(TokenKind.COLON, "expected ':' after the venue")
                val opens = cursor.peek().kind == TokenKind.LBRACE || cursor.peek().kind == TokenKind.STAR
                if (!opens && !cursor.atLadderSet()) cursor.error("expected '{' or '*' after '$venue:'")
                parseVenueSet(venue)
            }
        if (cursor.peek().kind != TokenKind.EOF) cursor.error("unexpected '${cursor.peek().lexeme}' after the set")
        return spec
    }

    private fun parseUnion(): SymbolSetSpec.Union {
        val operands = mutableListOf(cursor.expect(TokenKind.IDENT, "expected a set alias").lexeme)
        while (cursor.match(TokenKind.PLUS)) {
            operands.add(cursor.expect(TokenKind.IDENT, "expected a set alias after '+'").lexeme)
        }
        return SymbolSetSpec.Union(operands)
    }

    private fun parsePicked(venue: String): SymbolSetSpec.Picked {
        cursor.expect(TokenKind.LBRACE, "expected '{'")
        val names = mutableListOf(cursor.venueSymbol())
        while (cursor.match(TokenKind.COMMA)) {
            val name = cursor.venueSymbol()
            if (name in names) cursor.error("'$name' is listed twice in the set")
            names.add(name)
        }
        cursor.expect(TokenKind.RBRACE, "expected ',' or '}' in the symbol list")
        return SymbolSetSpec.Picked(venue, names)
    }

    private fun parseFiltered(venue: String): SymbolSetSpec.Filtered {
        val scope = cursor.setScope()
        val slot = scope.selector
        expectWord("WHERE", "expected WHERE after '$venue:${scope.family ?: "*"}${slot?.let { "@$it" }.orEmpty()}'")
        val filter = parseOr()
        expectWord("TOP", "expected TOP <n> after the WHERE filter")
        val count = cursor.expect(TokenKind.NUMBER, "expected a member count after TOP")
        val n =
            count.lexeme.toIntOrNull()?.takeIf { it > 0 }
                ?: cursor.errorAt(count, "TOP needs a positive whole number, got '${count.lexeme}'")
        cursor.expect(TokenKind.BY, "expected BY <field> after TOP $n")
        val by = parseField()
        val descending =
            when {
                isWord("DESC") -> true.also { cursor.advance() }
                isWord("ASC") -> false.also { cursor.advance() }
                else -> true
            }
        val dynamic = isWord("DYNAMIC").also { if (it) cursor.advance() }
        return SymbolSetSpec.Filtered(venue, filter, n, by, descending, dynamic, slot, scope.family)
    }

    private fun parseOr(): UniverseFilter {
        var left = parseAnd()
        while (cursor.match(TokenKind.OR)) left = UniverseFilter.Or(left, parseAnd())
        return left
    }

    private fun parseAnd(): UniverseFilter {
        var left = parseNot()
        while (cursor.match(TokenKind.AND)) left = UniverseFilter.And(left, parseNot())
        return left
    }

    private fun parseNot(): UniverseFilter =
        if (cursor.match(TokenKind.NOT)) UniverseFilter.Not(parseNot()) else parseClause()

    private fun parseClause(): UniverseFilter {
        if (cursor.match(TokenKind.LPAREN)) {
            val inner = parseOr()
            cursor.expect(TokenKind.RPAREN, "expected ')' to close the filter group")
            return inner
        }
        val field = parseField()
        if (cursor.match(TokenKind.IN)) {
            cursor.expect(TokenKind.LPAREN, "expected '(' after IN")
            val values = mutableListOf(parseLiteral())
            while (cursor.match(TokenKind.COMMA)) values.add(parseLiteral())
            cursor.expect(TokenKind.RPAREN, "expected ')' to close the IN list")
            return UniverseFilter.OneOf(field, values)
        }
        if (isWord("GLOB")) {
            cursor.advance()
            val pattern = cursor.expect(TokenKind.STRING, "expected a quoted glob pattern after GLOB").lexeme
            return UniverseFilter.Glob(field, pattern)
        }
        val op =
            when (cursor.peek().kind) {
                TokenKind.EQ, TokenKind.EQEQ -> CompareOp.EQ
                TokenKind.NEQ -> CompareOp.NE
                TokenKind.LT -> CompareOp.LT
                TokenKind.LE -> CompareOp.LE
                TokenKind.GT -> CompareOp.GT
                TokenKind.GE -> CompareOp.GE
                else -> cursor.error("expected a comparison, IN or GLOB after '${field.token}'")
            }
        cursor.advance()
        return UniverseFilter.Compare(field, op, parseLiteral())
    }

    private fun parseField(): CatalogField {
        val token = cursor.expectName("expected a catalog field")
        return CatalogField.parse(token.lexeme)
            ?: cursor.errorAt(token, "unknown catalog field '${token.lexeme}'; use ${CatalogField.spellings}")
    }

    private fun parseLiteral(): CatalogValue {
        val negative = cursor.match(TokenKind.MINUS)
        val token = cursor.peek()
        return when {
            token.kind == TokenKind.NUMBER -> {
                cursor.advance()
                // `1e3` and `1000` are one literal: a canonical spec never carries an exponent.
                val magnitude = BigDecimal(token.lexeme).let { if (it.scale() < 0) it.setScale(0) else it }
                CatalogValue.Num(if (negative) magnitude.negate() else magnitude)
            }
            negative -> cursor.error("expected a number after '-'")
            token.kind == TokenKind.STRING -> CatalogValue.Str(cursor.advance().lexeme)
            else -> CatalogValue.Str(cursor.expectName("expected a number, a quoted string or a word").lexeme)
        }
    }

    private fun isWord(word: String): Boolean = cursor.peek().lexeme.equals(word, ignoreCase = true)

    private fun expectWord(
        word: String,
        message: String,
    ) {
        if (!isWord(word)) cursor.error(message)
        cursor.advance()
    }
}
