package com.qkt.dsl.parse

import com.qkt.dsl.ast.CHAIN_BROKER
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.OPTIONS_BROKER
import com.qkt.dsl.ast.SeriesDecl
import com.qkt.dsl.ast.SeriesSource
import com.qkt.dsl.ast.StreamDecl
import com.qkt.instrument.ContinuousSelector

/**
 * Parses the right-hand side of one `SYMBOLS` declaration: a venue stream
 * (`<broker>:<symbol> EVERY <tf>`), a `BASKET` of declared streams, or a `SERIES` sampled from
 * account state.
 */
internal class StreamDeclParser(
    private val cursor: TokenCursor,
    private val literalParser: LiteralParser,
) {
    /**
     * Parse the stream body after `<alias> =`: `<broker>:<symbol>[@front|@next] EVERY <tf> [WARMUP <n> BARS]`.
     * A continuous-futures selector stays part of the symbol (`BTCUSDT@front`), so the stream's
     * identity, and every existing strategy's AST, are unchanged by the syntax.
     */
    fun parseStream(alias: String): StreamDecl {
        val broker = cursor.expect(TokenKind.IDENT, "expected broker prefix").lexeme
        for (reserved in listOf(CHAIN_BROKER, OPTIONS_BROKER)) {
            if (broker.equals(reserved, ignoreCase = true) &&
                broker != reserved
            ) {
                cursor.error("write the prefix as $reserved, not '$broker'")
            }
        }
        cursor.expect(TokenKind.COLON, "expected ':' between broker and symbol")
        val symbol =
            if (broker.equals(HUB_BROKER, ignoreCase = true)) {
                parseHubSymbol()
            } else if (broker == CHAIN_BROKER || broker == OPTIONS_BROKER) {
                parseDottedSymbol()
            } else {
                val name = cursor.expect(TokenKind.IDENT, "expected symbol after ':'").lexeme
                if (cursor.peek().kind == TokenKind.AT_SIGN) "$name@${parseSelector()}" else name
            }
        cursor.expect(TokenKind.EVERY, "expected EVERY")
        val timeframe = literalParser.parseTimeframe()
        val warmupBars: Int? =
            if (cursor.peek().kind == TokenKind.WARMUP) {
                cursor.advance()
                val numToken = cursor.expect(TokenKind.NUMBER, "expected integer bar count after WARMUP")
                val n =
                    numToken.lexeme.toIntOrNull()
                        ?: cursor.error("WARMUP count must be a positive integer, got '${numToken.lexeme}'")
                if (n <= 0) cursor.error("WARMUP count must be > 0, got $n")
                cursor.expect(TokenKind.BARS, "expected BARS after WARMUP count")
                n
            } else {
                null
            }
        return StreamDecl(
            alias = alias,
            broker = broker,
            symbol = symbol,
            timeframe = timeframe,
            warmupBars = warmupBars,
        )
    }

    /** The continuous-futures selector after `@` (`front`, `next`), validated against [ContinuousSelector]. */
    private fun parseSelector(): String {
        cursor.advance()
        val token = cursor.expect(TokenKind.IDENT, "expected a continuous selector after '@'").lexeme
        if (ContinuousSelector.parse(token) == null) {
            cursor.error(
                "unknown continuous selector '@$token'; use ${ContinuousSelector.entries.joinToString {
                    "@${it.token}"
                }}",
            )
        }
        return token
    }

    /**
     * A hub dataset or chain analytics name, which is dotted: `HUB:cal.high_impact`,
     * `HUB:cal.high_impact.USD`, `CHAIN:DERIBIT.BTC_USDC.atm_iv.30d`.
     *
     * Every other venue names an instrument with one identifier, so the general symbol rule is a
     * single IDENT. A hub dataset is addressed by a hierarchical name instead, and the lexer
     * splits on `.` because that character means field access everywhere else. Re-joining the
     * segments here keeps that meaning intact for every other stream while letting a hub alias
     * name what it actually needs to name.
     */
    private fun parseDottedSymbol(): String {
        val parts = mutableListOf(nameSegment())
        while (cursor.peek().kind == TokenKind.DOT) {
            cursor.advance()
            parts.add(nameSegment())
        }
        return parts.joinToString(".")
    }

    /**
     * A hub stream name: `<dataset>[.<SCOPE>]`, checked against the hub store's own naming rules.
     *
     * A scope can be a venue instrument (`DERIBIT:BTC-PERPETUAL`) or an issuer (`ISSUER:BRK-B`),
     * and the lexer splits those on `:` and `-`. Tokens written with no space between them are
     * one scope, so the parser re-joins them. The result is then held to the store's rules -- a
     * lower-case dotted dataset name and an upper-case scope with no dot -- because a name that
     * breaks them can never match a record, and failing here, at the line that wrote it, beats an
     * "unknown dataset" error at run start or a stream that silently reads nothing.
     */
    private fun parseHubSymbol(): String {
        val start = cursor.peek()
        val parts = mutableListOf(nameSegment())
        while (cursor.peek().kind == TokenKind.DOT) {
            cursor.advance()
            parts.add(nameSegment())
        }
        var last = parts.removeAt(parts.lastIndex)
        while (adjacentScopePiece()) {
            last += cursor.advance().lexeme
        }
        parts.add(last)
        val symbol = parts.joinToString(".")
        val scopeCandidate = parts.last()
        val hasScope =
            parts.size > 2 && scopeCandidate.any { it.isLetter() } && scopeCandidate == scopeCandidate.uppercase()
        val dataset = if (hasScope) parts.dropLast(1).joinToString(".") else symbol
        if (!HUB_DATASET.matches(dataset)) {
            cursor.errorAt(
                start,
                "hub dataset '$dataset' is not a valid name: lower-case segments joined by '.', the first a domain " +
                    "(cal.us_high, rates.us.dgs10); a scope goes last in upper case (cal.us_high.USD, " +
                    "pos.funding.DERIBIT:BTC-PERPETUAL, corp.earnings.ISSUER:BRK-B)",
            )
        }
        if (hasScope && !HUB_SCOPE.matches(scopeCandidate)) {
            cursor.errorAt(
                start,
                "hub scope '$scopeCandidate' must be upper case A-Z 0-9 _ : - with at least one letter",
            )
        }
        return symbol
    }

    /** The next token continues the scope: written with no space, and a piece a scope may contain. */
    private fun adjacentScopePiece(): Boolean {
        val prev = cursor.peekAt(-1)
        val next = cursor.peek()
        if (next.line != prev.line || next.col != prev.col + prev.lexeme.length) return false
        return next.kind == TokenKind.COLON ||
            next.kind == TokenKind.MINUS ||
            (next.lexeme.isNotEmpty() && next.lexeme.all { it.isLetterOrDigit() || it == '_' })
    }

    /**
     * One segment of a hub dataset name, accepting a token that happens to spell a keyword.
     *
     * A scope is written the way the world writes it -- `USD`, `EUR` -- and several of those are
     * already reserved words elsewhere in the grammar (`SIZING 10000 USD`). Matching on the shape
     * of the lexeme rather than on the token kind keeps a dataset free to be named after the thing
     * it describes, without the DSL's own vocabulary leaking into what a dataset may be called.
     */
    private fun nameSegment(): String {
        val token = cursor.peek()
        require(token.lexeme.isNotEmpty() && token.lexeme.all { it.isLetterOrDigit() || it == '_' }) {
            "expected a letters, digits or '_' name segment in a dotted HUB, CHAIN or OPTIONS symbol, got '${token.lexeme}'"
        }
        cursor.advance()
        return token.lexeme
    }

    /**
     * Parse the basket body after `<alias> =`:
     * `BASKET EQUAL_WEIGHT '[' <ident> (',' <ident>)+ ']' EVERY <tf>`.
     *
     * e.g. `antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h`.
     */
    fun parseBasket(alias: String): com.qkt.dsl.ast.BasketDecl {
        cursor.expect(TokenKind.BASKET, "expected BASKET")
        val weighting =
            when (cursor.peek().kind) {
                TokenKind.EQUAL_WEIGHT -> {
                    cursor.advance()
                    com.qkt.dsl.ast.BasketWeighting.EqualWeight
                }
                else -> cursor.error("expected basket weighting EQUAL_WEIGHT, got '${cursor.peek().lexeme}'")
            }
        cursor.expect(TokenKind.LBRACKET, "expected '[' to open basket constituents")
        val constituents = mutableListOf<String>()
        if (cursor.peek().kind != TokenKind.RBRACKET) {
            constituents.add(cursor.expect(TokenKind.IDENT, "expected constituent alias").lexeme)
            while (cursor.match(TokenKind.COMMA)) {
                constituents.add(cursor.expect(TokenKind.IDENT, "expected constituent alias after ','").lexeme)
            }
        }
        cursor.expect(TokenKind.RBRACKET, "expected ']' to close basket constituents")
        if (constituents.size < 2) {
            cursor.error("BASKET '$alias' needs at least 2 constituents, got ${constituents.size}")
        }
        cursor.expect(TokenKind.EVERY, "expected EVERY after basket constituents")
        val timeframe = literalParser.parseTimeframe()
        return com.qkt.dsl.ast.BasketDecl(
            alias = alias,
            weighting = weighting,
            constituents = constituents.toList(),
            timeframe = timeframe,
        )
    }

    /** Parse the series body after `<alias> =`: `SERIES ACCOUNT.EQUITY EVERY <tf>`. */
    fun parseSeries(alias: String): SeriesDecl {
        cursor.expect(TokenKind.SERIES, "expected SERIES")
        val source =
            when (cursor.peek().kind) {
                TokenKind.ACCOUNT -> {
                    cursor.advance()
                    cursor.expect(TokenKind.DOT, "expected '.' after ACCOUNT in SERIES declaration")
                    cursor.expect(TokenKind.EQUITY, "expected EQUITY after ACCOUNT. in SERIES declaration")
                    SeriesSource.ACCOUNT_EQUITY
                }
                else -> cursor.error("expected ACCOUNT.EQUITY after SERIES, got '${cursor.peek().lexeme}'")
            }
        cursor.expect(TokenKind.EVERY, "expected EVERY after SERIES source")
        val timeframe = literalParser.parseTimeframe()
        val windowMs =
            com.qkt.candles.TimeWindow
                .parse(timeframe)
                .durationMs
        if (windowMs < 60_000L) cursor.error("SERIES '$alias' timeframe must be >= 1m, got '$timeframe'")
        return SeriesDecl(alias = alias, source = source, timeframe = timeframe)
    }
}

/** The hub store's dataset rule (hubread.record.DATASET_RE): a lower-case dotted name of 2-5 segments. */
private val HUB_DATASET = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+){1,4}$")

/** The hub store's scope rule (hubread.record.SCOPE_RE): upper case, no dot, at least one letter. */
private val HUB_SCOPE = Regex("^(?=[A-Z0-9_:-]*[A-Z])[A-Z0-9_:-]{1,64}$")
