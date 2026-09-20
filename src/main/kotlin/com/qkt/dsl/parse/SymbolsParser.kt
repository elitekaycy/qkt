package com.qkt.dsl.parse

import com.qkt.dsl.ast.SeriesDecl
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.SyncGroupDecl

/**
 * Parses the `SYMBOLS` block: each `<alias> = ...` declaration (read by [StreamDeclParser]) and
 * the trailing `SYNCHRONIZE` groups, checking that every synchronized alias is declared and
 * belongs to one group only.
 */
internal class SymbolsParser(
    private val cursor: TokenCursor,
    private val literalParser: LiteralParser,
) {
    private val streamDeclParser = StreamDeclParser(cursor, literalParser)

    fun parseSymbols(): SymbolsBlock {
        val out = mutableListOf<StreamDecl>()
        val baskets = mutableListOf<com.qkt.dsl.ast.BasketDecl>()
        val series = mutableListOf<SeriesDecl>()
        cursor.expect(TokenKind.SYMBOLS, "expected SYMBOLS")
        do {
            val alias = cursor.expect(TokenKind.IDENT, "expected stream alias").lexeme
            cursor.expect(TokenKind.EQ, "expected '=' after stream alias")
            // The token after '=' disambiguates a basket (`BASKET ...`) from a real stream
            // (`<broker>:<symbol> ...`). A basket combines already-declared streams.
            if (cursor.peek().kind == TokenKind.BASKET) {
                baskets.add(streamDeclParser.parseBasket(alias))
            } else if (cursor.peek().kind == TokenKind.SERIES) {
                series.add(streamDeclParser.parseSeries(alias))
            } else {
                out.add(streamDeclParser.parseStream(alias))
            }
        } while (
            cursor.match(TokenKind.COMMA) ||
            // Comma between stream decls is optional: continue if the next two tokens
            // look like a new stream decl (`<alias> = ...`). Without this, only the
            // first stream parses when strategies use newline separation (#45).
            (cursor.peek().kind == TokenKind.IDENT && cursor.peekAt(1).kind == TokenKind.EQ)
        )

        // #45 — SYNCHRONIZE clauses at the end of the SYMBOLS block. Each clause:
        // `SYNCHRONIZE <ident> <ident> [<ident> …] [WITHIN <duration>]`.
        val groups = mutableListOf<SyncGroupDecl>()
        // A basket alias is a valid sync member too: a strategy may synchronize a real
        // stream with a basket so a cross-stream condition reads same-window bars.
        val declaredAliases = (out.map { it.alias } + baskets.map { it.alias } + series.map { it.alias }).toSet()
        val claimed = mutableMapOf<String, Int>()
        while (cursor.peek().kind == TokenKind.SYNCHRONIZE) {
            cursor.advance()
            val aliases = mutableListOf<String>()
            while (cursor.peek().kind == TokenKind.IDENT) {
                aliases.add(cursor.advance().lexeme)
            }
            if (aliases.size < 2) {
                cursor.error("SYNCHRONIZE requires at least 2 aliases, got ${aliases.size}")
            }
            val timeoutMs: Long? =
                if (cursor.peek().kind == TokenKind.WITHIN) {
                    cursor.advance()
                    literalParser.parseDuration().millis
                } else {
                    null
                }
            for (a in aliases) {
                if (a !in declaredAliases) {
                    cursor.error("SYNCHRONIZE alias '$a' is not declared in SYMBOLS")
                }
                val prevGroupIdx = claimed[a]
                if (prevGroupIdx != null) {
                    cursor.error(
                        "SYNCHRONIZE alias '$a' appears in more than one group " +
                            "(also in group ${prevGroupIdx + 1})",
                    )
                }
                claimed[a] = groups.size
            }
            groups.add(SyncGroupDecl(aliases = aliases.toList(), timeoutMs = timeoutMs))
        }

        return SymbolsBlock(streams = out, syncGroups = groups, baskets = baskets, series = series)
    }
}

/** What a `SYMBOLS` block declares: venue streams, sync groups, baskets and account series. */
internal data class SymbolsBlock(
    val streams: List<StreamDecl>,
    val syncGroups: List<SyncGroupDecl>,
    val baskets: List<com.qkt.dsl.ast.BasketDecl> = emptyList(),
    val series: List<SeriesDecl> = emptyList(),
)
