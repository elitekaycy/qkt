package com.qkt.dsl.parse

import com.qkt.common.Money
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotKind
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.dsl.ast.SnapshotTPast
import com.qkt.dsl.ast.StackAst
import com.qkt.dsl.ast.StackAtClause
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayer
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.SyncGroupDecl
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TimeOfDay
import com.qkt.dsl.ast.Timezone
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.ast.Window
import java.math.BigDecimal

class Parser(
    private val tokens: List<Token>,
) {
    private var pos = 0
    private val errors = mutableListOf<ParseError>()
    private var inStackLayerAt: Boolean = false

    fun parseFile(): ParseResult<ParsedFile> =
        when (peek().kind) {
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
                            line = peek().line,
                            col = peek().col,
                            message = "expected STRATEGY or PORTFOLIO at file start, got '${peek().lexeme}'",
                        ),
                    ),
                )
        }

    internal fun parsePortfolio(): ParseResult<com.qkt.dsl.ast.PortfolioAst> {
        var name = "_unparsed"
        var version = 0
        try {
            expect(TokenKind.PORTFOLIO, "expected PORTFOLIO")
            name = expect(TokenKind.IDENT, "expected portfolio name").lexeme
            expect(TokenKind.VERSION, "expected VERSION")
            val v = expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: error("VERSION must be an integer, got '${v.lexeme}'")
        } catch (_: ParseException) {
            synchronize()
        }

        val streams =
            if (peek().kind == TokenKind.SYMBOLS) {
                // Portfolios don't support SYNCHRONIZE in this phase (#45) —
                // discard any parsed syncGroups.
                tryParse { parseSymbols().streams } ?: emptyList()
            } else {
                emptyList()
            }

        val imports = mutableListOf<com.qkt.dsl.ast.ImportClause>()
        while (peek().kind == TokenKind.IMPORT) {
            tryParse { parseImport() }?.let { imports.add(it) }
        }

        val rules = mutableListOf<com.qkt.dsl.ast.PortfolioRule>()
        if (peek().kind == TokenKind.RULES) {
            advance()
            while (peek().kind == TokenKind.WHEN || peek().kind == TokenKind.RUN) {
                tryParse { parsePortfolioRule() }?.let { rules.add(it) }
            }
        }

        if (errors.isNotEmpty()) return ParseResult.Failure(errors.toList())
        return try {
            ParseResult.Success(
                com.qkt.dsl.ast
                    .PortfolioAst(name, version, streams, imports, rules),
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
        expect(TokenKind.IMPORT, "expected IMPORT")
        val pathTok = expect(TokenKind.STRING, "expected import path string")
        expect(TokenKind.AS, "expected AS after import path")
        val alias = expect(TokenKind.IDENT, "expected alias").lexeme
        val hold =
            if (peek().kind == TokenKind.HOLD) {
                advance()
                true
            } else {
                false
            }
        return com.qkt.dsl.ast
            .ImportClause(path = pathTok.lexeme, alias = alias, hold = hold)
    }

    internal fun parsePortfolioRule(): com.qkt.dsl.ast.PortfolioRule =
        when (peek().kind) {
            TokenKind.WHEN -> {
                advance()
                val cond = parseExpr()
                expect(TokenKind.RUN, "expected RUN after WHEN expression")
                val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                com.qkt.dsl.ast
                    .WhenRun(cond, alias)
            }
            TokenKind.RUN -> {
                advance()
                val alias = expect(TokenKind.IDENT, "expected child alias after RUN").lexeme
                com.qkt.dsl.ast
                    .AlwaysRun(alias)
            }
            else -> error("expected WHEN or RUN, got '${peek().lexeme}'")
        }

    fun parseStrategy(): ParseResult<StrategyAst> {
        var name = "_unparsed"
        var version = 0
        try {
            expect(TokenKind.STRATEGY, "expected STRATEGY")
            name = expect(TokenKind.IDENT, "expected strategy name").lexeme
            expect(TokenKind.VERSION, "expected VERSION")
            val v = expect(TokenKind.NUMBER, "expected integer version")
            version = v.lexeme.toIntOrNull() ?: error("VERSION must be an integer, got '${v.lexeme}'")
        } catch (_: ParseException) {
            synchronize()
        }

        val defaults =
            if (peek().kind == TokenKind.DEFAULTS) {
                tryParse { parseDefaults() }
            } else {
                null
            }

        val symbolsBlock =
            if (peek().kind == TokenKind.SYMBOLS) {
                tryParse { parseSymbols() } ?: SymbolsBlock(emptyList(), emptyList())
            } else {
                SymbolsBlock(emptyList(), emptyList())
            }
        val streams = symbolsBlock.streams
        val syncGroups = symbolsBlock.syncGroups

        val lets =
            if (peek().kind == TokenKind.LET) {
                tryParse { parseLet() } ?: emptyList()
            } else {
                emptyList()
            }

        val schedules =
            if (peek().kind == TokenKind.SCHEDULE) {
                tryParse { parseSchedules() } ?: emptyList()
            } else {
                emptyList()
            }

        val rules =
            if (peek().kind == TokenKind.RULES) {
                tryParse { parseRules() } ?: emptyList()
            } else {
                emptyList()
            }

        if (errors.isNotEmpty()) return ParseResult.Failure(errors.toList())
        return ParseResult.Success(
            StrategyAst(
                name = name,
                version = version,
                streams = streams,
                constants = emptyList(),
                lets = lets,
                defaults = defaults,
                rules = rules,
                syncGroups = syncGroups,
                schedules = schedules,
            ),
        )
    }

    private fun parseLet(): List<LetDecl> {
        val out = mutableListOf<LetDecl>()
        expect(TokenKind.LET, "expected LET")
        do {
            val name = expect(TokenKind.IDENT, "expected let name").lexeme
            expect(TokenKind.EQ, "expected '=' after let name")
            val expr = parseExpr()
            out.add(LetDecl(name, expr))
        } while (match(TokenKind.COMMA))
        return out
    }

    private fun parseExpr(): ExprAst = parseOrExpr()

    private fun parseOrExpr(): ExprAst {
        var lhs = parseAndExpr()
        while (peek().kind == TokenKind.OR) {
            advance()
            val rhs = parseAndExpr()
            lhs = BinaryOp(BinOp.OR, lhs, rhs)
        }
        return lhs
    }

    private fun parseAndExpr(): ExprAst {
        var lhs = parseNotExpr()
        while (peek().kind == TokenKind.AND) {
            advance()
            val rhs = parseNotExpr()
            lhs = BinaryOp(BinOp.AND, lhs, rhs)
        }
        return lhs
    }

    private fun parseNotExpr(): ExprAst {
        if (match(TokenKind.NOT)) return UnaryOp(UnOp.NOT, parseNotExpr())
        return parseCmpExpr()
    }

    private fun parseCmpExpr(): ExprAst {
        var lhs = parseAddExpr()
        while (true) {
            val k = peek().kind
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
                advance()
                val rhs = parseAddExpr()
                lhs = CmpOp(op, lhs, rhs)
                continue
            }
            when (k) {
                TokenKind.BETWEEN -> {
                    advance()
                    val lo = parseAddExpr()
                    expect(TokenKind.AND, "expected AND between BETWEEN bounds")
                    val hi = parseAddExpr()
                    lhs = Between(lhs, lo, hi)
                }
                TokenKind.IN -> {
                    advance()
                    expect(TokenKind.LBRACKET, "expected '[' after IN")
                    val members = mutableListOf<ExprAst>()
                    if (peek().kind != TokenKind.RBRACKET) {
                        members.add(parseExpr())
                        while (match(TokenKind.COMMA)) members.add(parseExpr())
                    }
                    expect(TokenKind.RBRACKET, "expected ']' to close IN list")
                    lhs = InList(lhs, members)
                }
                TokenKind.CROSSES -> {
                    advance()
                    val dir =
                        when (peek().kind) {
                            TokenKind.ABOVE -> {
                                advance()
                                CrossDir.ABOVE
                            }
                            TokenKind.BELOW -> {
                                advance()
                                CrossDir.BELOW
                            }
                            else -> error("expected ABOVE or BELOW after CROSSES, got '${peek().lexeme}'")
                        }
                    val rhs = parseAddExpr()
                    lhs = Crosses(dir, lhs, rhs)
                }
                TokenKind.IS -> {
                    advance()
                    val negated = match(TokenKind.NOT)
                    expect(TokenKind.NULL, "expected NULL after IS${if (negated) " NOT" else ""}")
                    lhs = IsNull(lhs, negated)
                }
                else -> return lhs
            }
        }
    }

    private fun parseAddExpr(): ExprAst {
        var lhs = parseMulExpr()
        while (peek().kind == TokenKind.PLUS || peek().kind == TokenKind.MINUS) {
            val op = if (advance().kind == TokenKind.PLUS) BinOp.ADD else BinOp.SUB
            val rhs = parseMulExpr()
            lhs = BinaryOp(op, lhs, rhs)
        }
        return lhs
    }

    private fun parseMulExpr(): ExprAst {
        var lhs = parseUnaryExpr()
        while (peek().kind == TokenKind.STAR || peek().kind == TokenKind.SLASH) {
            val op = if (advance().kind == TokenKind.STAR) BinOp.MUL else BinOp.DIV
            val rhs = parseUnaryExpr()
            lhs = BinaryOp(op, lhs, rhs)
        }
        return lhs
    }

    private fun parseUnaryExpr(): ExprAst {
        if (match(TokenKind.MINUS)) return UnaryOp(UnOp.NEG, parseUnaryExpr())
        return parsePrimary()
    }

    private fun parsePrimary(): ExprAst {
        val t = peek()
        return when (t.kind) {
            TokenKind.NUMBER -> {
                advance()
                NumLit(BigDecimal(t.lexeme))
            }
            TokenKind.DURATION -> {
                val d = parseDuration()
                NumLit(BigDecimal.valueOf(d.millis))
            }
            TokenKind.STRING -> {
                advance()
                StringLit(t.lexeme)
            }
            TokenKind.TRUE -> {
                advance()
                BoolLit(true)
            }
            TokenKind.FALSE -> {
                advance()
                BoolLit(false)
            }
            TokenKind.ENTRY_QTY -> {
                advance()
                com.qkt.dsl.ast.EntryQty
            }
            TokenKind.MAX, TokenKind.MIN, TokenKind.MEAN, TokenKind.SUM -> parseAggregate()
            TokenKind.CASE -> parseCaseWhen()
            TokenKind.ACCOUNT -> {
                advance()
                expect(TokenKind.DOT, "expected '.' after ACCOUNT")
                AccountRef(expectFieldName().lexeme)
            }
            TokenKind.POSITION -> {
                advance()
                expect(TokenKind.DOT, "expected '.' after POSITION")
                val streamAlias = expectFieldName().lexeme
                if (peek().kind == TokenKind.DOT) {
                    advance()
                    val accessor = expectFieldName().lexeme
                    when (accessor) {
                        "quantity", "qty" -> PositionRef(streamAlias)
                        "entry_price", "avg_price", "avg_entry_price" ->
                            StateAccessor(StateSource.POSITION_AVG_PRICE, streamAlias)
                        "pnl" -> StateAccessor(StateSource.POSITION_PNL, streamAlias)
                        "realized_pnl" -> StateAccessor(StateSource.POSITION_REALIZED_PNL, streamAlias)
                        "unrealized_pnl" -> StateAccessor(StateSource.POSITION_UNREALIZED_PNL, streamAlias)
                        "holding_duration" -> StateAccessor(StateSource.POSITION_HOLDING_DURATION, streamAlias)
                        "mfe" -> StateAccessor(StateSource.POSITION_MFE, streamAlias)
                        "trades_today" -> StateAccessor(StateSource.POSITION_TRADES_TODAY, streamAlias)
                        "last_trade_at" -> StateAccessor(StateSource.POSITION_LAST_TRADE_AT, streamAlias)
                        else -> {
                            errors += ParseError(t.line, t.col, "unknown POSITION accessor: $accessor")
                            PositionRef(streamAlias)
                        }
                    }
                } else {
                    PositionRef(streamAlias)
                }
            }
            TokenKind.POSITION_AVG_PRICE -> {
                advance()
                expect(TokenKind.DOT, "expected '.' after POSITION_AVG_PRICE")
                StateAccessor(StateSource.POSITION_AVG_PRICE, expectFieldName().lexeme)
            }
            TokenKind.OPEN_ORDERS -> {
                advance()
                expect(TokenKind.DOT, "expected '.' after OPEN_ORDERS")
                StateAccessor(StateSource.OPEN_ORDERS, expectFieldName().lexeme)
            }
            TokenKind.SYMBOL -> {
                advance()
                Ref("__SYMBOL__")
            }
            TokenKind.NOW -> {
                advance()
                if (peek().kind == TokenKind.DOT) {
                    advance()
                    // NOW.<field>. `WEEKDAY` is also a SCHEDULE token (#77), so we
                    // accept either an IDENT or that specific keyword here and read
                    // the lexeme — keeps `NOW.weekday` working as a field access.
                    val fieldTok =
                        when (peek().kind) {
                            TokenKind.IDENT, TokenKind.WEEKDAY -> advance()
                            else -> expect(TokenKind.IDENT, "expected NOW field name")
                        }
                    val field =
                        when (fieldTok.lexeme.uppercase()) {
                            "HOUR_UTC" -> NowField.HOUR_UTC
                            "MINUTE_UTC" -> NowField.MINUTE_UTC
                            "WEEKDAY" -> NowField.WEEKDAY
                            "DATE_UTC" -> NowField.DATE_UTC
                            "EPOCH_MS" -> NowField.EPOCH_MS
                            else -> {
                                errors +=
                                    ParseError(fieldTok.line, fieldTok.col, "unknown NOW field: ${fieldTok.lexeme}")
                                NowField.EPOCH_MS
                            }
                        }
                    NowAccessor(field)
                } else {
                    NowAccessor(NowField.EPOCH_MS)
                }
            }
            // TokenKind.LOG is included here because `log` is also a reserved action
            // keyword (`LOG "message"` in THEN). In expression position, `log(` must
            // bind to the math function — the action parser is only reached from action
            // position, so this overlap is unambiguous.
            TokenKind.IDENT, TokenKind.OPEN, TokenKind.CLOSE, TokenKind.LOG -> {
                if (inStackLayerAt && t.kind == TokenKind.IDENT && t.lexeme == "entry") {
                    advance()
                    return StackEntryRef
                }
                val name = advance().lexeme
                when {
                    match(TokenKind.LPAREN) -> {
                        val args = mutableListOf<ExprAst>()
                        if (peek().kind != TokenKind.RPAREN) {
                            args.add(parseExpr())
                            while (match(TokenKind.COMMA)) args.add(parseExpr())
                        }
                        expect(TokenKind.RPAREN, "expected ')' after arguments")
                        // Scalar math functions (abs, sqrt, log, exp, pow, …) route through
                        // FuncCall — pure functions on numeric values, no warmup or per-bar state.
                        // Everything else stays IndicatorCall for the indicator-binding path.
                        if (com.qkt.dsl.stdlib.FuncRegistry
                                .has(name.uppercase())
                        ) {
                            FuncCall(name.uppercase(), args)
                        } else {
                            IndicatorCall(name, args)
                        }
                    }
                    match(TokenKind.DOT) -> {
                        val field = expectFieldName().lexeme
                        StreamFieldRef(name, field)
                    }
                    match(TokenKind.AT_SIGN) -> Ref(name, parseSnapshotKind())
                    else -> Ref(name)
                }
            }
            TokenKind.LPAREN -> {
                advance()
                val e = parseExpr()
                expect(TokenKind.RPAREN, "expected ')'")
                e
            }
            else -> error("expected expression, got '${t.lexeme}'")
        }
    }

    private fun parseAggregate(): ExprAst {
        val fnTok = advance()
        val fn =
            when (fnTok.kind) {
                TokenKind.MAX -> AggFn.MAX
                TokenKind.MIN -> AggFn.MIN
                TokenKind.MEAN -> AggFn.MEAN
                TokenKind.SUM -> AggFn.SUM
                else -> error("unreachable")
            }
        expect(TokenKind.LPAREN, "expected '(' after ${fnTok.lexeme}")
        val series = parseExpr()
        expect(TokenKind.RPAREN, "expected ')' to close aggregate args")
        expect(TokenKind.SINCE, "expected SINCE after aggregate")
        val window = parseWindow()
        return Aggregate(fn, series, window)
    }

    private fun parseWindow(): Window {
        val t = peek()
        return when {
            t.kind == TokenKind.OPEN -> {
                advance()
                SinceOpen
            }
            t.kind == TokenKind.IDENT && t.lexeme.equals("T", ignoreCase = true) -> {
                advance()
                expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: error("expected positive integer after T-")
                SinceTPast(n)
            }
            else -> error("expected OPEN or T-N for window, got '${t.lexeme}'")
        }
    }

    private fun parseSnapshotKind(): SnapshotKind {
        val t = peek()
        return when {
            t.kind == TokenKind.BUY -> {
                advance()
                SnapshotBuy
            }
            t.kind == TokenKind.SELL -> {
                advance()
                SnapshotSell
            }
            t.kind == TokenKind.OPEN -> {
                advance()
                SnapshotOpen
            }
            t.kind == TokenKind.IDENT && t.lexeme.equals("T", ignoreCase = true) -> {
                advance()
                expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: error("expected positive integer after T-")
                SnapshotTPast(n)
            }
            else -> error("expected snapshot kind (buy/sell/open/T-N), got '${t.lexeme}'")
        }
    }

    internal fun parseOrderType(): OrderTypeAst =
        when (peek().kind) {
            TokenKind.MARKET -> {
                advance()
                Market
            }
            TokenKind.LIMIT -> {
                advance()
                expect(TokenKind.AT, "expected AT after LIMIT")
                Limit(parseExpr())
            }
            TokenKind.STOP -> {
                advance()
                expect(TokenKind.AT, "expected AT after STOP")
                val stopPrice = parseExpr()
                if (peek().kind == TokenKind.LIMIT) {
                    advance()
                    expect(TokenKind.AT, "expected AT after LIMIT")
                    StopLimit(stopPrice, parseExpr())
                } else {
                    Stop(stopPrice)
                }
            }
            TokenKind.TRAILING -> {
                advance()
                when (peek().kind) {
                    TokenKind.BY -> {
                        advance()
                        TrailingBy(parseExpr())
                    }
                    TokenKind.PCT -> {
                        advance()
                        TrailingPct(parseExpr())
                    }
                    else -> error("expected BY or PCT after TRAILING, got '${peek().lexeme}'")
                }
            }
            else -> error("expected order type (MARKET/LIMIT/STOP/TRAILING), got '${peek().lexeme}'")
        }

    internal fun parseTif(): TifAst =
        when (peek().kind) {
            TokenKind.GTC -> {
                advance()
                Gtc
            }
            TokenKind.IOC -> {
                advance()
                Ioc
            }
            TokenKind.FOK -> {
                advance()
                Fok
            }
            TokenKind.DAY -> {
                advance()
                Day
            }
            TokenKind.GTD -> {
                advance()
                Gtd(parseExpr())
            }
            else -> error("expected TIF (GTC/IOC/FOK/DAY/GTD), got '${peek().lexeme}'")
        }

    internal fun parseChildPrice(): ChildPriceAst =
        when (peek().kind) {
            TokenKind.AT -> {
                advance()
                ChildAt(parseExpr())
            }
            TokenKind.BY -> {
                advance()
                ChildBy(parseExpr())
            }
            TokenKind.PCT -> {
                advance()
                ChildPct(parseExpr())
            }
            TokenKind.RR -> {
                advance()
                ChildRr(parseExpr())
            }
            TokenKind.TRAILING -> {
                advance()
                val distance = parseExpr()
                expect(TokenKind.AFTER, "expected AFTER after TRAILING <distance>")
                expect(TokenKind.MFE, "expected MFE after AFTER")
                expect(TokenKind.GE, "expected '>=' after MFE")
                val threshold = parseExpr()
                ChildArmedTrail(distance, threshold)
            }
            else -> error("expected child price (AT/BY/PCT/RR/TRAILING), got '${peek().lexeme}'")
        }

    internal fun parseRules(): List<RuleAst> {
        expect(TokenKind.RULES, "expected RULES")
        val out = mutableListOf<RuleAst>()
        while (peek().kind != TokenKind.EOF) {
            when (peek().kind) {
                TokenKind.WHEN -> tryParse { parseWhenThen() }?.let { out.add(it) }
                TokenKind.FOR -> tryParse { parseForEach() }?.let { out.addAll(it) }
                else -> {
                    tryParse {
                        error("expected WHEN or FOR EACH in RULES, got '${peek().lexeme}'")
                    }
                }
            }
        }
        return out
    }

    private fun parseWhenThen(): WhenThen {
        expect(TokenKind.WHEN, "expected WHEN")
        val cond = parseExpr()
        expect(TokenKind.THEN, "expected THEN after WHEN condition")
        val first = parseAction()
        if (peek().kind != TokenKind.SEMICOLON) return WhenThen(cond, first)
        val actions = mutableListOf(first)
        while (match(TokenKind.SEMICOLON)) {
            if (!isActionStart(peek().kind)) break
            actions.add(parseAction())
        }
        return WhenThen(cond, Block(actions))
    }

    private fun isActionStart(k: TokenKind): Boolean =
        k == TokenKind.BUY ||
            k == TokenKind.SELL ||
            k == TokenKind.CLOSE ||
            k == TokenKind.CLOSE_ALL ||
            k == TokenKind.FLATTEN ||
            k == TokenKind.CANCEL ||
            k == TokenKind.CANCEL_ALL ||
            k == TokenKind.LOG ||
            k == TokenKind.OCO_ENTRY

    private fun parseForEach(): List<RuleAst> {
        expect(TokenKind.FOR, "expected FOR")
        expect(TokenKind.EACH, "expected EACH after FOR")
        val iterVar = expect(TokenKind.IDENT, "expected iteration variable").lexeme
        expect(TokenKind.IN, "expected IN after iteration variable")
        expect(TokenKind.LBRACKET, "expected '[' to open stream alias list")
        val aliases = mutableListOf<String>()
        if (peek().kind != TokenKind.RBRACKET) {
            aliases.add(expect(TokenKind.IDENT, "expected stream alias").lexeme)
            while (match(TokenKind.COMMA)) {
                aliases.add(expect(TokenKind.IDENT, "expected stream alias").lexeme)
            }
        }
        expect(TokenKind.RBRACKET, "expected ']' to close stream alias list")
        expect(TokenKind.DO, "expected DO after stream alias list")
        val template = parseWhenThen()
        return aliases.map { alias -> substituteIterVar(template, iterVar, alias) }
    }

    internal fun parseAction(): ActionAst =
        when (peek().kind) {
            TokenKind.BUY -> {
                advance()
                val stream = expect(TokenKind.IDENT, "expected stream alias after BUY").lexeme
                Buy(stream, parseActionOpts())
            }
            TokenKind.SELL -> {
                advance()
                val stream = expect(TokenKind.IDENT, "expected stream alias after SELL").lexeme
                Sell(stream, parseActionOpts())
            }
            TokenKind.CLOSE -> {
                advance()
                val stream = expect(TokenKind.IDENT, "expected stream alias after CLOSE").lexeme
                Close(stream)
            }
            TokenKind.CLOSE_ALL -> {
                advance()
                CloseAll
            }
            TokenKind.FLATTEN -> {
                advance()
                CloseAll
            }
            TokenKind.CANCEL -> {
                advance()
                val stream = expect(TokenKind.IDENT, "expected stream alias after CANCEL").lexeme
                Cancel(stream)
            }
            TokenKind.CANCEL_ALL -> {
                advance()
                CancelAll
            }
            TokenKind.LOG -> parseLogAction()
            TokenKind.OCO_ENTRY -> parseOcoEntry()
            else -> error("expected action keyword, got '${peek().lexeme}'")
        }

    private fun parseOcoEntry(): ActionAst {
        expect(TokenKind.OCO_ENTRY, "expected OCO_ENTRY")
        expect(TokenKind.LBRACE, "expected '{' after OCO_ENTRY")
        val leg1 = parseAction()
        if (leg1 !is Buy && leg1 !is Sell) {
            error("OCO_ENTRY legs must be BUY or SELL, got ${leg1::class.simpleName}")
        }
        expect(TokenKind.COMMA, "expected ',' between OCO_ENTRY legs")
        val leg2 = parseAction()
        if (leg2 !is Buy && leg2 !is Sell) {
            error("OCO_ENTRY legs must be BUY or SELL, got ${leg2::class.simpleName}")
        }
        expect(TokenKind.RBRACE, "expected '}' to close OCO_ENTRY (exactly two legs)")
        return OcoEntry(leg1, leg2)
    }

    private fun parseLogAction(): Log {
        expect(TokenKind.LOG, "expected LOG")
        val level =
            when (peek().kind) {
                TokenKind.WARN -> {
                    advance()
                    LogLevel.WARN
                }
                TokenKind.ERROR -> {
                    advance()
                    LogLevel.ERROR
                }
                TokenKind.DEBUG -> {
                    advance()
                    LogLevel.DEBUG
                }
                else -> LogLevel.INFO
            }
        val message = expect(TokenKind.STRING, "expected string literal after LOG").lexeme
        val fields = linkedMapOf<String, ExprAst>()
        while (peek().kind == TokenKind.IDENT && tokens.getOrNull(pos + 1)?.kind == TokenKind.EQ) {
            val name = expect(TokenKind.IDENT, "expected field name").lexeme
            expect(TokenKind.EQ, "expected '='")
            val expr = parseExpr()
            if (fields.containsKey(name)) {
                error("duplicate LOG field '$name'")
            }
            fields[name] = expr
        }
        val placeholders = LOG_PLACEHOLDER_REGEX.findAll(message).map { it.groupValues[1] }.toSet()
        val unmatched = placeholders - fields.keys
        if (unmatched.isNotEmpty()) {
            error("LOG placeholder(s) without matching field: ${unmatched.joinToString()}")
        }
        return Log(level, message, fields)
    }

    private fun parseActionOpts(): ActionOpts {
        var sizing: SizingAst? = null
        var orderType: OrderTypeAst? = null
        var tif: TifAst? = null
        var bracket: BracketAst? = null
        var oco: OcoAst? = null
        var stack: StackAst? = null
        var stackAts: List<StackAtClause> = emptyList()
        loop@ while (true) {
            when (peek().kind) {
                TokenKind.SIZING -> {
                    advance()
                    sizing = parseSizing()
                }
                TokenKind.ORDER_TYPE -> {
                    advance()
                    expect(TokenKind.EQ, "expected '=' after ORDER_TYPE")
                    orderType = parseOrderType()
                }
                TokenKind.TIF -> {
                    advance()
                    tif = parseTif()
                }
                TokenKind.BRACKET -> {
                    advance()
                    bracket = parseBracket()
                }
                TokenKind.OCO -> {
                    advance()
                    oco = parseOco()
                }
                TokenKind.STACK -> {
                    advance()
                    stack = parseStackClause()
                }
                TokenKind.STACK_AT -> {
                    stackAts += parseStackAtClause()
                }
                else -> break@loop
            }
        }
        val finalStack = stack
        if (sizing != null && finalStack is StackLayers) {
            error(
                "STACK layer-list cannot be combined with outer SIZING; specify size on each layer or remove the layer list",
            )
        }
        return ActionOpts(sizing, orderType ?: com.qkt.dsl.ast.Market, tif, bracket, oco, finalStack, stackAts)
    }

    /**
     * Phase 27: `STACK_AT MFE >= <expr> WITHIN <duration> SIZING <sizing> BRACKET { ... }`.
     *
     * The clause attaches to its parent BUY/SELL action. The stack engine fires the stack
     * when the parent leg's MFE crosses the threshold within the duration window.
     */
    private fun parseStackAtClause(): StackAtClause {
        expect(TokenKind.STACK_AT, "expected STACK_AT")
        expect(TokenKind.MFE, "expected MFE after STACK_AT")
        expect(TokenKind.GE, "expected '>=' after MFE in STACK_AT")
        val threshold = parseExpr()
        expect(TokenKind.WITHIN, "expected WITHIN after MFE threshold in STACK_AT")
        val duration = parseDuration()
        expect(TokenKind.SIZING, "expected SIZING in STACK_AT clause")
        val sizing = parseSizing()
        expect(TokenKind.BRACKET, "expected BRACKET in STACK_AT clause")
        val bracket = parseBracket()
        return StackAtClause(
            mfeThreshold = threshold,
            withinDuration = duration,
            sizing = sizing,
            bracket = bracket,
        )
    }

    internal fun parseStackClause(): StackAst {
        // STACK <count> SPACING <expr> [ABOVE|BELOW] [WITHIN <duration>]
        // STACK [ <layers> ] [WITHIN <duration>]   (added in Task 5)
        return if (peek().kind == TokenKind.LBRACKET) {
            parseStackLayers()
        } else {
            parseStackSpacing()
        }
    }

    internal fun parseStackSpacing(): StackSpacing {
        val countTok = expect(TokenKind.NUMBER, "expected count after STACK")
        val count =
            countTok.lexeme.toIntOrNull()
                ?: error("STACK count must be a positive integer, got '${countTok.lexeme}'")
        if (count < 1) error("STACK count must be >= 1, got $count")
        expect(TokenKind.SPACING, "expected SPACING after STACK count")
        val spacing = parseExpr()
        val direction =
            when (peek().kind) {
                TokenKind.ABOVE -> {
                    advance()
                    StackDirection.ABOVE
                }
                TokenKind.BELOW -> {
                    advance()
                    StackDirection.BELOW
                }
                else -> StackDirection.TRADE_DIRECTION
            }
        val within = if (peek().kind == TokenKind.WITHIN) parseWithin() else null
        return StackSpacing(count, spacing, direction, within)
    }

    internal fun parseStackLayers(): StackLayers {
        expect(TokenKind.LBRACKET, "expected '[' to open layer list")
        val layers = mutableListOf<StackLayer>()
        if (peek().kind == TokenKind.RBRACKET) {
            error("STACK layer list must not be empty")
        }
        layers.add(parseLayer(isFirst = true))
        while (peek().kind == TokenKind.COMMA) {
            advance()
            if (peek().kind == TokenKind.RBRACKET) break
            layers.add(parseLayer(isFirst = false))
        }
        expect(TokenKind.RBRACKET, "expected ']' to close layer list")
        val within = if (peek().kind == TokenKind.WITHIN) parseWithin() else null
        return StackLayers(layers, within)
    }

    internal fun parseLayer(isFirst: Boolean): StackLayer {
        val sizing = parseSizing()
        inStackLayerAt = true
        try {
            val orderType: OrderTypeAst? =
                when (peek().kind) {
                    TokenKind.MARKET, TokenKind.LIMIT, TokenKind.STOP -> parseOrderType()
                    else -> null
                }
            val priceFromOrderType: ExprAst? =
                when (orderType) {
                    is Limit -> orderType.price
                    is Stop -> orderType.price
                    is StopLimit -> orderType.stopPrice
                    else -> null
                }
            val explicitAt: ExprAst? =
                if (peek().kind == TokenKind.AT) {
                    if (priceFromOrderType != null) {
                        error(
                            "STACK layer with LIMIT/STOP/STOPLIMIT cannot have a separate AT clause; " +
                                "the order type's price is the trigger",
                        )
                    }
                    advance()
                    parseExpr()
                } else {
                    null
                }
            val at: ExprAst? = priceFromOrderType ?: explicitAt
            if (!isFirst && at == null) {
                error("STACK layers after the first must have a trigger (via AT or LIMIT/STOP price)")
            }
            return StackLayer(sizing, orderType, at)
        } finally {
            inStackLayerAt = false
        }
    }

    internal fun parseWithin(): DurationAst {
        expect(TokenKind.WITHIN, "expected WITHIN")
        return parseDuration()
    }

    internal fun parseDuration(): DurationAst {
        val tok = expect(TokenKind.DURATION, "expected duration literal (e.g., 1h, 30m)")
        val lex = tok.lexeme
        val n =
            lex.dropLast(1).toLongOrNull()
                ?: error("invalid duration literal '$lex'")
        val unit = lex.last()
        val millis =
            when (unit) {
                's' -> n * 1_000L
                'm' -> n * 60_000L
                'h' -> n * 3_600_000L
                'd' -> n * 86_400_000L
                else -> error("unknown duration unit '$unit' in '$lex'")
            }
        return DurationAst(millis)
    }

    internal fun parseDefaults(): DefaultsBlock {
        expect(TokenKind.DEFAULTS, "expected DEFAULTS")
        expect(TokenKind.LBRACE, "expected '{' after DEFAULTS")
        var sizing: SizingAst? = null
        var orderType: OrderTypeAst? = null
        var tif: TifAst? = null
        var stopLoss: ChildPriceAst? = null
        var takeProfit: ChildPriceAst? = null
        var trailing: OrderTypeAst? = null
        while (peek().kind != TokenKind.RBRACE && peek().kind != TokenKind.EOF) {
            when (peek().kind) {
                TokenKind.SIZING -> {
                    advance()
                    expect(TokenKind.EQ, "expected '=' after SIZING in DEFAULTS")
                    sizing = parseSizing()
                }
                TokenKind.STOP_LOSS -> {
                    advance()
                    expect(TokenKind.EQ, "expected '=' after STOP_LOSS in DEFAULTS")
                    stopLoss = parseChildPrice()
                }
                TokenKind.TAKE_PROFIT -> {
                    advance()
                    expect(TokenKind.EQ, "expected '=' after TAKE_PROFIT in DEFAULTS")
                    takeProfit = parseChildPrice()
                }
                TokenKind.TIF -> {
                    advance()
                    expect(TokenKind.EQ, "expected '=' after TIF in DEFAULTS")
                    tif = parseTif()
                }
                TokenKind.ORDER_TYPE -> {
                    advance()
                    expect(TokenKind.EQ, "expected '=' after ORDER_TYPE in DEFAULTS")
                    orderType = parseOrderType()
                }
                TokenKind.TRAILING -> {
                    advance()
                    expect(TokenKind.EQ, "expected '=' after TRAILING in DEFAULTS")
                    trailing = parseOrderType()
                }
                else -> error("expected DEFAULTS clause keyword, got '${peek().lexeme}'")
            }
        }
        expect(TokenKind.RBRACE, "expected '}' to close DEFAULTS")
        return DefaultsBlock(sizing, orderType, tif, stopLoss, takeProfit, trailing)
    }

    internal fun parseBracket(): BracketAst {
        expect(TokenKind.LBRACE, "expected '{' to open BRACKET block")
        var stopLoss: ChildPriceAst? = null
        var takeProfit: ChildPriceAst? = null
        do {
            when (peek().kind) {
                TokenKind.STOP -> {
                    advance()
                    expect(TokenKind.LOSS, "expected LOSS after STOP")
                    stopLoss = parseChildPrice()
                }
                TokenKind.TAKE -> {
                    advance()
                    expect(TokenKind.PROFIT, "expected PROFIT after TAKE")
                    if (peek().kind == TokenKind.TRAILING) {
                        error(
                            "TAKE PROFIT TRAILING is not supported — TRAILING is stop-only " +
                                "(armed trail). Use TAKE PROFIT AT/BY/PCT/RR.",
                        )
                    }
                    takeProfit = parseChildPrice()
                }
                else -> error("expected STOP LOSS or TAKE PROFIT in BRACKET, got '${peek().lexeme}'")
            }
        } while (match(TokenKind.COMMA))
        expect(TokenKind.RBRACE, "expected '}' to close BRACKET block")
        return BracketAst(stopLoss, takeProfit)
    }

    internal fun parseOco(): OcoAst {
        expect(TokenKind.LBRACE, "expected '{' to open OCO block")
        var stop: ChildPriceAst? = null
        var limit: ChildPriceAst? = null
        do {
            when (peek().kind) {
                TokenKind.STOP -> {
                    advance()
                    expect(TokenKind.AT, "expected AT after STOP in OCO")
                    stop = ChildAt(parseExpr())
                }
                TokenKind.LIMIT -> {
                    advance()
                    expect(TokenKind.AT, "expected AT after LIMIT in OCO")
                    limit = ChildAt(parseExpr())
                }
                else -> error("expected STOP AT or LIMIT AT in OCO, got '${peek().lexeme}'")
            }
        } while (match(TokenKind.COMMA))
        expect(TokenKind.RBRACE, "expected '}' to close OCO block")
        val s = stop ?: error("OCO requires a STOP AT child")
        val l = limit ?: error("OCO requires a LIMIT AT child")
        return OcoAst(s, l)
    }

    internal fun parseSizing(): SizingAst {
        val k = peek().kind
        return when (k) {
            TokenKind.RISK -> {
                advance()
                if (match(TokenKind.DOLLAR)) {
                    SizeRiskAbs(parseExpr())
                } else {
                    SizeRiskFrac(parseExpr())
                }
            }
            TokenKind.POSITION -> {
                // SIZING POSITION.<alias>
                advance()
                expect(TokenKind.DOT, "expected '.' after POSITION")
                val alias = expectFieldName().lexeme
                SizePositionFull(alias)
            }
            else -> {
                val e = parseExpr()
                when (peek().kind) {
                    TokenKind.USD -> {
                        advance()
                        SizeNotional(e)
                    }
                    TokenKind.PCT -> {
                        advance()
                        expect(TokenKind.RISK, "expected RISK after PCT in SIZING")
                        require(e is NumLit) {
                            "SIZING N PCT RISK requires a numeric literal for N, got non-literal expression"
                        }
                        val pct = e.value
                        require(pct.signum() > 0) {
                            "SIZING N PCT RISK requires N > 0, got $pct"
                        }
                        SizeRiskFrac(NumLit(pct.divide(BigDecimal(100), Money.CONTEXT)))
                    }
                    TokenKind.PERCENT -> {
                        advance()
                        expect(TokenKind.OF, "expected OF after %")
                        when (peek().kind) {
                            TokenKind.EQUITY -> {
                                advance()
                                SizePctEquity(e)
                            }
                            TokenKind.BALANCE -> {
                                advance()
                                SizePctBalance(e)
                            }
                            else -> error("expected EQUITY or BALANCE after % OF, got '${peek().lexeme}'")
                        }
                    }
                    else -> SizeQty(e)
                }
            }
        }
    }

    private fun parseCaseWhen(): ExprAst {
        expect(TokenKind.CASE, "expected CASE")
        val branches = mutableListOf<Pair<ExprAst, ExprAst>>()
        while (peek().kind == TokenKind.WHEN) {
            advance()
            val cond = parseExpr()
            expect(TokenKind.THEN, "expected THEN in CASE branch")
            val body = parseExpr()
            branches.add(cond to body)
        }
        if (branches.isEmpty()) error("CASE requires at least one WHEN branch")
        val elseExpr =
            if (match(TokenKind.ELSE)) {
                parseExpr()
            } else {
                error("CASE requires an ELSE branch")
            }
        expect(TokenKind.END, "expected END to close CASE")
        return CaseWhen(branches, elseExpr)
    }

    internal data class SymbolsBlock(
        val streams: List<StreamDecl>,
        val syncGroups: List<SyncGroupDecl>,
    )

    private fun parseSymbols(): SymbolsBlock {
        val out = mutableListOf<StreamDecl>()
        expect(TokenKind.SYMBOLS, "expected SYMBOLS")
        do {
            val alias = expect(TokenKind.IDENT, "expected stream alias").lexeme
            expect(TokenKind.EQ, "expected '=' after stream alias")
            val broker = expect(TokenKind.IDENT, "expected broker prefix").lexeme
            expect(TokenKind.COLON, "expected ':' between broker and symbol")
            val symbol = expect(TokenKind.IDENT, "expected symbol after ':'").lexeme
            expect(TokenKind.EVERY, "expected EVERY")
            val timeframe =
                if (peek().kind == TokenKind.DURATION) {
                    advance().lexeme
                } else {
                    val tfNum = expect(TokenKind.NUMBER, "expected timeframe count").lexeme
                    val tfUnit = expect(TokenKind.IDENT, "expected timeframe unit (s/m/h/d)").lexeme
                    "$tfNum$tfUnit"
                }
            val warmupBars: Int? =
                if (peek().kind == TokenKind.WARMUP) {
                    advance()
                    val numToken = expect(TokenKind.NUMBER, "expected integer bar count after WARMUP")
                    val n =
                        numToken.lexeme.toIntOrNull()
                            ?: error("WARMUP count must be a positive integer, got '${numToken.lexeme}'")
                    if (n <= 0) error("WARMUP count must be > 0, got $n")
                    expect(TokenKind.BARS, "expected BARS after WARMUP count")
                    n
                } else {
                    null
                }
            out.add(
                StreamDecl(
                    alias = alias,
                    broker = broker,
                    symbol = symbol,
                    timeframe = timeframe,
                    warmupBars = warmupBars,
                ),
            )
        } while (
            match(TokenKind.COMMA) ||
            // Comma between stream decls is optional: continue if the next two tokens
            // look like a new stream decl (`<alias> = ...`). Without this, only the
            // first stream parses when strategies use newline separation (#45).
            (peek().kind == TokenKind.IDENT && tokens[pos + 1].kind == TokenKind.EQ)
        )

        // #45 — SYNCHRONIZE clauses at the end of the SYMBOLS block. Each clause:
        // `SYNCHRONIZE <ident> <ident> [<ident> …] [WITHIN <duration>]`.
        val groups = mutableListOf<SyncGroupDecl>()
        val declaredAliases = out.map { it.alias }.toSet()
        val claimed = mutableMapOf<String, Int>()
        while (peek().kind == TokenKind.SYNCHRONIZE) {
            advance()
            val aliases = mutableListOf<String>()
            while (peek().kind == TokenKind.IDENT) {
                aliases.add(advance().lexeme)
            }
            if (aliases.size < 2) {
                error("SYNCHRONIZE requires at least 2 aliases, got ${aliases.size}")
            }
            val timeoutMs: Long? =
                if (peek().kind == TokenKind.WITHIN) {
                    advance()
                    parseDuration().millis
                } else {
                    null
                }
            for (a in aliases) {
                if (a !in declaredAliases) {
                    error("SYNCHRONIZE alias '$a' is not declared in SYMBOLS")
                }
                val prevGroupIdx = claimed[a]
                if (prevGroupIdx != null) {
                    error(
                        "SYNCHRONIZE alias '$a' appears in more than one group " +
                            "(also in group ${prevGroupIdx + 1})",
                    )
                }
                claimed[a] = groups.size
            }
            groups.add(SyncGroupDecl(aliases = aliases.toList(), timeoutMs = timeoutMs))
        }

        return SymbolsBlock(streams = out, syncGroups = groups)
    }

    /**
     * Parse one `SCHEDULE` block (#77). Each clause is one of:
     *   - `AT <time> UTC THEN <action>` — single one-off, fires daily
     *   - `AT <t1>, <t2>, … UTC THEN <action>` — same action at multiple times
     *   - `EVERY HOUR AT :<min> THEN <action>`
     *   - `EVERY DAY AT <time> UTC THEN <action>`
     *   - `EVERY WEEKDAY AT <time> UTC THEN <action>`
     *
     * UTC is required on every `AT <time>` form. Block continues until a non-trigger
     * token (typically `RULES`).
     */
    private fun parseSchedules(): List<ScheduleDecl> {
        expect(TokenKind.SCHEDULE, "expected SCHEDULE")
        val out = mutableListOf<ScheduleDecl>()
        while (peek().kind == TokenKind.AT || peek().kind == TokenKind.EVERY) {
            val triggers = mutableListOf<ScheduleTrigger>()
            if (peek().kind == TokenKind.AT) {
                advance() // AT
                val times = mutableListOf<TimeOfDay>()
                times.add(parseTimeOfDay())
                while (peek().kind == TokenKind.COMMA) {
                    advance()
                    times.add(parseTimeOfDay())
                }
                val tz = parseTimezone("SCHEDULE AT")
                for (t in times) {
                    triggers.add(ScheduleTrigger.At(time = t, tz = tz))
                }
            } else {
                triggers.add(parseScheduleTrigger())
            }
            expect(TokenKind.THEN, "expected THEN after SCHEDULE trigger(s)")
            val action = parseAction()
            out.add(ScheduleDecl(triggers = triggers, action = action))
        }
        return out
    }

    /** Parse one non-list trigger: `EVERY HOUR AT :NN`, `EVERY DAY AT ...`, `EVERY WEEKDAY AT ...`. */
    private fun parseScheduleTrigger(): ScheduleTrigger {
        expect(TokenKind.EVERY, "expected EVERY")
        return when (peek().kind) {
            TokenKind.HOUR -> {
                advance()
                expect(TokenKind.AT, "expected AT after EVERY HOUR")
                expect(TokenKind.COLON, "expected ':' before minute offset")
                val mTok = expect(TokenKind.NUMBER, "expected minute 0-59")
                val m = mTok.lexeme.toIntOrNull() ?: error("expected integer minute, got '${mTok.lexeme}'")
                ScheduleTrigger.EveryHour(minuteOffset = m)
            }
            TokenKind.DAY -> {
                advance()
                expect(TokenKind.AT, "expected AT after EVERY DAY")
                val time = parseTimeOfDay()
                val tz = parseTimezone("EVERY DAY")
                ScheduleTrigger.EveryDay(time = time, tz = tz)
            }
            TokenKind.WEEKDAY -> {
                advance()
                expect(TokenKind.AT, "expected AT after EVERY WEEKDAY")
                val time = parseTimeOfDay()
                val tz = parseTimezone("EVERY WEEKDAY")
                ScheduleTrigger.EveryWeekday(time = time, tz = tz)
            }
            else -> error("expected HOUR, DAY, or WEEKDAY after EVERY, got '${peek().lexeme}'")
        }
    }

    /**
     * Parse the timezone tag that follows a time literal in a `SCHEDULE` trigger.
     * One of `UTC` / `NY` / `LONDON` / `TOKYO` / `SYDNEY` / `CHICAGO` / `BROKER`.
     * [where] is the trigger label used in the error message.
     */
    private fun parseTimezone(where: String): Timezone =
        when (peek().kind) {
            TokenKind.UTC -> {
                advance()
                Timezone.UTC
            }
            TokenKind.NY -> {
                advance()
                Timezone.NY
            }
            TokenKind.LONDON -> {
                advance()
                Timezone.LONDON
            }
            TokenKind.TOKYO -> {
                advance()
                Timezone.TOKYO
            }
            TokenKind.SYDNEY -> {
                advance()
                Timezone.SYDNEY
            }
            TokenKind.CHICAGO -> {
                advance()
                Timezone.CHICAGO
            }
            TokenKind.BROKER -> {
                advance()
                Timezone.BROKER
            }
            else ->
                error(
                    "$where requires an explicit timezone " +
                        "(UTC/NY/LONDON/TOKYO/SYDNEY/CHICAGO/BROKER), got '${peek().lexeme}'",
                )
        }

    /**
     * Parse `HH:MM` or `HH:MM:SS` into a [TimeOfDay]. Out-of-range values are
     * rejected by [TimeOfDay.init].
     */
    private fun parseTimeOfDay(): TimeOfDay {
        val hourTok = expect(TokenKind.NUMBER, "expected hour")
        expect(TokenKind.COLON, "expected ':' after hour")
        val minTok = expect(TokenKind.NUMBER, "expected minute")
        val second: Int =
            if (peek().kind == TokenKind.COLON) {
                advance()
                val secTok = expect(TokenKind.NUMBER, "expected second")
                secTok.lexeme.toIntOrNull() ?: error("expected integer second, got '${secTok.lexeme}'")
            } else {
                0
            }
        return TimeOfDay(
            hour = hourTok.lexeme.toIntOrNull() ?: error("expected integer hour, got '${hourTok.lexeme}'"),
            minute = minTok.lexeme.toIntOrNull() ?: error("expected integer minute, got '${minTok.lexeme}'"),
            second = second,
        )
    }

    private inline fun <T> tryParse(block: () -> T): T? =
        try {
            block()
        } catch (_: ParseException) {
            synchronize()
            null
        }

    private fun peek(): Token = tokens[pos]

    private fun advance(): Token = tokens[pos++]

    private fun match(kind: TokenKind): Boolean =
        if (peek().kind == kind) {
            advance()
            true
        } else {
            false
        }

    private fun expect(
        kind: TokenKind,
        msg: String,
    ): Token {
        if (peek().kind == kind) return advance()
        error("$msg, got '${peek().lexeme}'")
    }

    private fun expectFieldName(): Token {
        val t = peek()
        // After a '.', any IDENT or keyword-with-identifier-shaped lexeme is a valid field name.
        if (t.kind == TokenKind.IDENT || isIdentLikeLexeme(t.lexeme)) {
            return advance()
        }
        error("expected field name, got '${t.lexeme}'")
    }

    private fun isIdentLikeLexeme(s: String): Boolean {
        if (s.isEmpty()) return false
        val first = s[0]
        if (!(first.isLetter() || first == '_')) return false
        return s.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun error(msg: String): Nothing {
        val t = peek()
        val e = ParseError(t.line, t.col, msg)
        errors.add(e)
        throw ParseException(e)
    }

    private fun synchronize() {
        while (peek().kind !in SYNC_KINDS) advance()
    }

    companion object {
        private val LOG_PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")

        private val SYNC_KINDS =
            setOf(
                TokenKind.DEFAULTS,
                TokenKind.SYMBOLS,
                TokenKind.LET,
                TokenKind.RULES,
                TokenKind.WHEN,
                TokenKind.FOR,
                TokenKind.EOF,
            )
    }
}
