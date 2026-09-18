package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.AllocateBlock
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.ParamDecl
import com.qkt.dsl.ast.PortfolioAllocationMethod
import com.qkt.dsl.ast.RegimeBlock
import com.qkt.dsl.ast.RegimeConditionalState
import com.qkt.dsl.ast.RegimeDefaultState
import com.qkt.dsl.ast.RegimeState
import com.qkt.dsl.ast.SequenceDecl
import com.qkt.dsl.ast.SeriesDecl
import com.qkt.dsl.ast.SeriesSource
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.SyncGroupDecl
import com.qkt.dsl.ast.TifAst
import java.math.BigDecimal

/**
 * Recursive-descent parser for `.qkt` files: turns the [Lexer]'s tokens into a [StrategyAst] or
 * a portfolio AST.
 *
 * Parser is the entry point and the wiring. The grammar itself lives in focused components
 * (expressions, actions, bracket and sizing clauses, top-level blocks) that share one
 * [TokenCursor], so errors from every component land in one list and recovery resumes from one
 * position.
 */
class Parser(
    tokens: List<Token>,
) {
    private val cursor = TokenCursor(tokens)
    private val scope = ParseScope()
    private val literalParser = LiteralParser(cursor)
    private val expressionParser = ExpressionParser(cursor, scope, literalParser)
    private val sizingParser = SizingParser(cursor, expressionParser)
    private val orderTypeParser = OrderTypeParser(cursor, scope, expressionParser)
    private val bracketParser = BracketParser(cursor, literalParser, expressionParser)
    private val actionParser =
        ActionParser(cursor, scope, literalParser, expressionParser, sizingParser, orderTypeParser, bracketParser)
    private val ruleParser = RuleParser(cursor, expressionParser, actionParser)
    private val scheduleParser = ScheduleParser(cursor, actionParser)
    private val declarationParser = DeclarationParser(cursor, literalParser, expressionParser)
    private val defaultsParser = DefaultsParser(cursor, sizingParser, orderTypeParser, bracketParser)

    fun parseFile(): ParseResult<ParsedFile> =
        when (cursor.peek().kind) {
            TokenKind.STRATEGY ->
                when (val r = parseStrategy()) {
                    is ParseResult.Success -> ParseResult.Success(ParsedFile.StrategyFile(r.value))
                    is ParseResult.Failure -> ParseResult.Failure(r.errors)
                }
            TokenKind.PORTFOLIO ->
                when (val r = parsePortfolio()) {
                    is ParseResult.Success -> ParseResult.Success(ParsedFile.PortfolioFile(r.value))
                    is ParseResult.Failure -> ParseResult.Failure(r.errors)
                }
            else ->
                ParseResult.Failure(
                    listOf(
                        ParseError(
                            line = cursor.peek().line,
                            col = cursor.peek().col,
                            message = "expected STRATEGY or PORTFOLIO at file start, got '${cursor.peek().lexeme}'",
                        ),
                    ),
                )
        }

    internal fun parsePortfolio(): ParseResult<com.qkt.dsl.ast.PortfolioAst> {
        var name = "_unparsed"
        var version = 0
        var capital: java.math.BigDecimal? = null
        try {
            cursor.expect(TokenKind.PORTFOLIO, "expected PORTFOLIO")
            name = cursor.expect(TokenKind.IDENT, "expected portfolio name").lexeme
            cursor.expect(TokenKind.VERSION, "expected VERSION")
            val v = cursor.expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: cursor.error("VERSION must be an integer, got '${v.lexeme}'")
            if (cursor.peek().kind == TokenKind.CAPITAL) {
                cursor.advance()
                val capTok = cursor.expect(TokenKind.NUMBER, "expected number after CAPITAL")
                capital = capTok.lexeme.toBigDecimalOrNull()
                    ?: cursor.error("CAPITAL must be a number, got '${capTok.lexeme}'")
            }
        } catch (_: ParseException) {
            cursor.synchronize()
        }

        val streams =
            if (cursor.peek().kind == TokenKind.SYMBOLS) {
                // Portfolios don't support SYNCHRONIZE in this phase (#45) —
                // discard any parsed syncGroups.
                cursor.tryParse { parseSymbols().streams } ?: emptyList()
            } else {
                emptyList()
            }

        val imports = mutableListOf<com.qkt.dsl.ast.ImportClause>()
        while (cursor.peek().kind == TokenKind.IMPORT) {
            cursor.tryParse { parseImport() }?.let { imports.add(it) }
        }

        val regimes =
            if (cursor.peek().kind == TokenKind.REGIMES) {
                cursor.tryParse { parseRegimes() }
            } else {
                null
            }

        val allocate =
            if (cursor.peek().kind == TokenKind.ALLOCATE) {
                cursor.tryParse { parseAllocate() }
            } else {
                null
            }

        val rules = mutableListOf<com.qkt.dsl.ast.PortfolioRule>()
        if (cursor.peek().kind == TokenKind.RULES) {
            cursor.advance()
            while (cursor.peek().kind == TokenKind.WHEN || cursor.peek().kind == TokenKind.RUN) {
                cursor.tryParse { parsePortfolioRule() }?.let { rules.add(it) }
            }
        }

        cursor.requireEof()
        if (cursor.errors.isNotEmpty()) return ParseResult.Failure(cursor.errors.toList())
        return try {
            ParseResult.Success(
                com.qkt.dsl.ast
                    .PortfolioAst(name, version, streams, imports, rules, capital, regimes, allocate),
            )
        } catch (e: IllegalArgumentException) {
            ParseResult.Failure(
                listOf(
                    ParseError(
                        line = 0,
                        col = 0,
                        message = e.message ?: "PORTFOLIO validation failed",
                    ),
                ),
            )
        }
    }

    internal fun parseImport(): com.qkt.dsl.ast.ImportClause {
        cursor.expect(TokenKind.IMPORT, "expected IMPORT")
        val pathTok = cursor.expect(TokenKind.STRING, "expected import path string")
        cursor.expect(TokenKind.AS, "expected AS after import path")
        val alias = cursor.expect(TokenKind.IDENT, "expected alias").lexeme
        val hold =
            if (cursor.peek().kind == TokenKind.HOLD) {
                cursor.advance()
                true
            } else {
                false
            }
        return com.qkt.dsl.ast
            .ImportClause(path = pathTok.lexeme, alias = alias, hold = hold)
    }

    internal fun parsePortfolioRule(): com.qkt.dsl.ast.PortfolioRule =
        when (cursor.peek().kind) {
            TokenKind.WHEN -> {
                cursor.advance()
                val cond = expressionParser.parseExpr()
                cursor.expect(TokenKind.RUN, "expected RUN after WHEN expression")
                val alias = cursor.expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                val weight = parseOptionalWeight()
                com.qkt.dsl.ast
                    .WhenRun(cond, alias, weight, parseOptionalOverrides())
            }
            TokenKind.RUN -> {
                cursor.advance()
                val alias = cursor.expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                val weight = parseOptionalWeight()
                com.qkt.dsl.ast
                    .AlwaysRun(alias, weight, parseOptionalOverrides())
            }
            else -> cursor.error("expected WHEN or RUN, got '${cursor.peek().lexeme}'")
        }

    private fun parseOptionalWeight(): java.math.BigDecimal? =
        if (cursor.peek().kind == TokenKind.WEIGHT) {
            cursor.advance()
            val tok = cursor.expect(TokenKind.NUMBER, "expected number after WEIGHT")
            tok.lexeme.toBigDecimalOrNull() ?: cursor.error("WEIGHT must be a number, got '${tok.lexeme}'")
        } else {
            null
        }

    private fun parseOptionalOverrides(): Map<String, ExprAst> {
        if (cursor.peek().kind != TokenKind.OVERRIDE) return emptyMap()
        cursor.advance()
        cursor.expect(TokenKind.LBRACE, "expected '{' after OVERRIDE")
        val out = LinkedHashMap<String, ExprAst>()
        if (cursor.peek().kind != TokenKind.RBRACE) {
            do {
                val key = cursor.expect(TokenKind.IDENT, "expected override key").lexeme
                if (out.containsKey(key)) cursor.error("duplicate OVERRIDE key '$key'")
                cursor.expect(TokenKind.EQ, "expected '=' after override key")
                out[key] = literalParser.parseLiteral()
            } while (cursor.match(TokenKind.COMMA))
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close OVERRIDE")
        return out
    }

    internal fun parseRegimes(): RegimeBlock {
        cursor.expect(TokenKind.REGIMES, "expected REGIMES")
        cursor.expect(TokenKind.NAME, "expected NAME after REGIMES")
        val blockName = cursor.expect(TokenKind.IDENT, "expected regime block name").lexeme
        val states = mutableListOf<RegimeState>()
        while (cursor.peek().kind == TokenKind.STATE) {
            states.add(parseRegimeState())
        }
        return RegimeBlock(blockName, states)
    }

    internal fun parseRegimeState(): RegimeState {
        cursor.expect(TokenKind.STATE, "expected STATE")
        val name = cursor.expect(TokenKind.IDENT, "expected state name").lexeme
        return when (cursor.peek().kind) {
            TokenKind.WHEN -> {
                cursor.advance()
                RegimeConditionalState(name, expressionParser.parseExpr())
            }
            TokenKind.DEFAULT -> {
                cursor.advance()
                RegimeDefaultState(name)
            }
            else -> cursor.error("expected WHEN or DEFAULT after STATE '$name'")
        }
    }

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

    fun parseStrategy(): ParseResult<StrategyAst> {
        var name = "_unparsed"
        var version = 0
        try {
            cursor.expect(TokenKind.STRATEGY, "expected STRATEGY")
            name = cursor.expectName("expected strategy name").lexeme
            cursor.expect(TokenKind.VERSION, "expected VERSION")
            val v = cursor.expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: cursor.error("VERSION must be an integer, got '${v.lexeme}'")
        } catch (_: ParseException) {
            cursor.synchronize()
        }

        val defaults =
            if (cursor.peek().kind == TokenKind.DEFAULTS) {
                cursor.tryParse { defaultsParser.parseDefaults() }
            } else {
                null
            }

        val symbolsBlock =
            if (cursor.peek().kind == TokenKind.SYMBOLS) {
                cursor.tryParse { parseSymbols() } ?: SymbolsBlock(emptyList(), emptyList())
            } else {
                SymbolsBlock(emptyList(), emptyList())
            }
        val streams = symbolsBlock.streams
        val syncGroups = symbolsBlock.syncGroups
        val baskets = symbolsBlock.baskets
        val series = symbolsBlock.series

        val params =
            run {
                val acc = mutableListOf<ParamDecl>()
                while (cursor.peek().kind == TokenKind.PARAM) {
                    cursor.tryParse { declarationParser.parseParams() }?.let { acc.addAll(it) }
                }
                acc
            }

        val lets =
            run {
                // Repeated `LET` LINES are the documented form (docs/reference/dsl/let-defaults.md
                // and the session-range example in indicators.md both show two). `parseLet` consumes
                // one LET keyword plus its comma-separated bindings, so a single `if` silently
                // stopped after the first line and every later LET fell through to the
                // "unexpected token after the last recognized block" error. Loop like PARAM does.
                val acc = mutableListOf<LetDecl>()
                while (cursor.peek().kind == TokenKind.LET) {
                    val parsed = cursor.tryParse { declarationParser.parseLet() }
                    if (parsed == null) break
                    acc.addAll(parsed)
                }
                acc
            }

        val schedules =
            if (cursor.peek().kind == TokenKind.SCHEDULE) {
                cursor.tryParse { scheduleParser.parseSchedules() } ?: emptyList()
            } else {
                emptyList()
            }

        val sequences =
            run {
                val acc = mutableListOf<SequenceDecl>()
                while (cursor.peek().kind == TokenKind.SEQUENCE) {
                    cursor.tryParse { declarationParser.parseSequence() }?.let { acc.add(it) }
                }
                acc
            }

        val rules =
            if (cursor.peek().kind == TokenKind.RULES) {
                cursor.tryParse { ruleParser.parseRules() } ?: emptyList()
            } else {
                emptyList()
            }

        cursor.requireEof()
        if (cursor.errors.isNotEmpty()) return ParseResult.Failure(cursor.errors.toList())
        return ParseResult.Success(
            StrategyAst(
                name = name,
                version = version,
                streams = streams,
                constants = emptyList(),
                lets = lets,
                params = params,
                defaults = defaults,
                rules = rules,
                syncGroups = syncGroups,
                schedules = schedules,
                baskets = baskets,
                series = series,
                sequences = sequences,
            ),
        )
    }

    internal data class SymbolsBlock(
        val streams: List<StreamDecl>,
        val syncGroups: List<SyncGroupDecl>,
        val baskets: List<com.qkt.dsl.ast.BasketDecl> = emptyList(),
        val series: List<SeriesDecl> = emptyList(),
    )

    private fun parseSymbols(): SymbolsBlock {
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
                baskets.add(parseBasket(alias))
            } else if (cursor.peek().kind == TokenKind.SERIES) {
                series.add(parseSeries(alias))
            } else {
                out.add(parseStream(alias))
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

    /** Parse the series body after `<alias> =`: `SERIES ACCOUNT.EQUITY EVERY <tf>`. */
    private fun parseSeries(alias: String): SeriesDecl {
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

    /** Parse the stream body after `<alias> =`: `<broker>:<symbol> EVERY <tf> [WARMUP <n> BARS]`. */
    private fun parseStream(alias: String): StreamDecl {
        val broker = cursor.expect(TokenKind.IDENT, "expected broker prefix").lexeme
        cursor.expect(TokenKind.COLON, "expected ':' between broker and symbol")
        val symbol =
            if (broker.equals(HUB_BROKER, ignoreCase = true)) {
                parseDottedSymbol()
            } else {
                cursor.expect(TokenKind.IDENT, "expected symbol after ':'").lexeme
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

    /**
     * A hub dataset name, which is dotted: `HUB:cal.high_impact` or `HUB:cal.high_impact.USD`.
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
            "expected a name segment in a hub dataset, got '${token.lexeme}'"
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
    private fun parseBasket(alias: String): com.qkt.dsl.ast.BasketDecl {
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

    internal fun parseAction(): ActionAst = actionParser.parseAction()

    internal fun parseBracket(): BracketAst = bracketParser.parseBracket()

    internal fun parseOco(): OcoAst = bracketParser.parseOco()

    internal fun parseChildPrice(): ChildPriceAst = bracketParser.parseChildPrice()

    internal fun parseOrderType(): OrderTypeAst = orderTypeParser.parseOrderType()

    internal fun parseTif(): TifAst = orderTypeParser.parseTif()

    internal fun parseSizing(): SizingAst = sizingParser.parseSizing()
}
