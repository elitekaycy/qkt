package com.qkt.dsl.parse

import com.qkt.common.Money
import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.AllocateBlock
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CalendarWindow
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
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.Day
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.ExitField
import com.qkt.dsl.ast.ExitHooksAst
import com.qkt.dsl.ast.ExitRef
import com.qkt.dsl.ast.ExitRelativeLimit
import com.qkt.dsl.ast.ExitRelativeStop
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Fok
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Gtd
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.Ioc
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchCloseBeyond
import com.qkt.dsl.ast.LatchConfirm
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchFirstTick
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.LatchOrder
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchStop
import com.qkt.dsl.ast.LatchTimeInBreach
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
import com.qkt.dsl.ast.ParamDecl
import com.qkt.dsl.ast.PortfolioAllocationMethod
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.RegimeBlock
import com.qkt.dsl.ast.RegimeConditionalState
import com.qkt.dsl.ast.RegimeDefaultState
import com.qkt.dsl.ast.RegimeState
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SequenceDecl
import com.qkt.dsl.ast.SequenceStageDecl
import com.qkt.dsl.ast.SeriesDecl
import com.qkt.dsl.ast.SeriesSource
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
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
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.Stop
import com.qkt.dsl.ast.StopLimit
import com.qkt.dsl.ast.StopStepAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.SyncGroupDecl
import com.qkt.dsl.ast.TifAst
import com.qkt.dsl.ast.TimeOfDay
import com.qkt.dsl.ast.TimeTightenAst
import com.qkt.dsl.ast.Timezone
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.TrailingBy
import com.qkt.dsl.ast.TrailingPct
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.ast.Window
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
                val cond = parseExpr()
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
                out[key] = parseLiteral()
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
                RegimeConditionalState(name, parseExpr())
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
                parseDuration()
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
                cursor.tryParse { parseDefaults() }
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
                    cursor.tryParse { parseParams() }?.let { acc.addAll(it) }
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
                    val parsed = cursor.tryParse { parseLet() }
                    if (parsed == null) break
                    acc.addAll(parsed)
                }
                acc
            }

        val schedules =
            if (cursor.peek().kind == TokenKind.SCHEDULE) {
                cursor.tryParse { parseSchedules() } ?: emptyList()
            } else {
                emptyList()
            }

        val sequences =
            run {
                val acc = mutableListOf<SequenceDecl>()
                while (cursor.peek().kind == TokenKind.SEQUENCE) {
                    cursor.tryParse { parseSequence() }?.let { acc.add(it) }
                }
                acc
            }

        val rules =
            if (cursor.peek().kind == TokenKind.RULES) {
                cursor.tryParse { parseRules() } ?: emptyList()
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

    private fun parseLet(): List<LetDecl> {
        val out = mutableListOf<LetDecl>()
        cursor.expect(TokenKind.LET, "expected LET")
        do {
            val name = cursor.expect(TokenKind.IDENT, "expected let name").lexeme
            cursor.expect(TokenKind.EQ, "expected '=' after let name")
            val expr = parseExpr()
            out.add(LetDecl(name, expr))
        } while (cursor.match(TokenKind.COMMA))
        return out
    }

    private fun parseLiteral(): ExprAst {
        val negate = cursor.match(TokenKind.MINUS)
        return when (cursor.peek().kind) {
            TokenKind.NUMBER -> {
                val t = cursor.advance()
                val n = t.lexeme.toBigDecimalOrNull() ?: cursor.error("expected a number literal, got '${t.lexeme}'")
                NumLit(if (negate) n.negate() else n)
            }
            TokenKind.TRUE -> {
                cursor.advance()
                if (negate) cursor.error("cannot negate a boolean")
                BoolLit(true)
            }
            TokenKind.FALSE -> {
                cursor.advance()
                if (negate) cursor.error("cannot negate a boolean")
                BoolLit(false)
            }
            TokenKind.STRING -> {
                val t = cursor.advance()
                if (negate) cursor.error("cannot negate a string")
                StringLit(t.lexeme)
            }
            else ->
                cursor.error(
                    "expected a literal value (number, TRUE/FALSE, or string), got '${cursor.peek().lexeme}'",
                )
        }
    }

    private fun parseParams(): List<ParamDecl> {
        val out = mutableListOf<ParamDecl>()
        cursor.expect(TokenKind.PARAM, "expected PARAM")
        val name = cursor.expect(TokenKind.IDENT, "expected param name").lexeme
        cursor.expect(TokenKind.EQ, "expected '=' after param name")
        out.add(ParamDecl(name, parseLiteral()))
        return out
    }

    private fun parseExpr(): ExprAst = parseOrExpr()

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
        return parsePrimary()
    }

    private fun parsePrimary(): ExprAst {
        val t = cursor.peek()
        return when (t.kind) {
            TokenKind.NUMBER -> {
                cursor.advance()
                NumLit(BigDecimal(t.lexeme))
            }
            TokenKind.DURATION -> {
                val d = parseDuration()
                NumLit(BigDecimal.valueOf(d.millis))
            }
            TokenKind.STRING -> {
                cursor.advance()
                StringLit(t.lexeme)
            }
            TokenKind.TRUE -> {
                cursor.advance()
                BoolLit(true)
            }
            TokenKind.FALSE -> {
                cursor.advance()
                BoolLit(false)
            }
            TokenKind.ENTRY_QTY -> {
                cursor.advance()
                com.qkt.dsl.ast.EntryQty
            }
            TokenKind.EXIT -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after EXIT")
                val field = cursor.expectFieldName().lexeme.uppercase()
                ExitRef(
                    when (field) {
                        "PRICE" -> ExitField.PRICE
                        "SIDE" -> ExitField.SIDE
                        "QTY", "QUANTITY" -> ExitField.QTY
                        "PNL" -> ExitField.PNL
                        "REASON" -> ExitField.REASON
                        else -> cursor.error("unknown EXIT field '$field'")
                    },
                )
            }
            TokenKind.MAX, TokenKind.MIN -> parseAggregateOrFunction()
            TokenKind.MEAN, TokenKind.SUM -> parseAggregate()
            TokenKind.CASE -> parseCaseWhen()
            TokenKind.ACCOUNT -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after ACCOUNT")
                AccountRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.STREAK -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after STREAK")
                StreakRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.TRADES -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after TRADES")
                TradesRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.COOLDOWN -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after COOLDOWN")
                CooldownRef(cursor.expectFieldName().lexeme)
            }
            TokenKind.SEQUENCE -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after SEQUENCE")
                val sequenceName = cursor.expectFieldName().lexeme
                cursor.expect(TokenKind.DOT, "expected '.' after SEQUENCE name")
                val first = cursor.expectFieldName().lexeme
                if (first == "stage" || first == "complete") {
                    SequenceAccessor(sequenceName, null, first)
                } else {
                    cursor.expect(TokenKind.DOT, "expected '.' after SEQUENCE stage name")
                    SequenceAccessor(sequenceName, first, cursor.expectFieldName().lexeme)
                }
            }
            TokenKind.POSITION -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after POSITION")
                val streamAlias = cursor.expectFieldName().lexeme
                if (cursor.peek().kind == TokenKind.DOT) {
                    cursor.advance()
                    val accessor = cursor.expectFieldName().lexeme
                    when (accessor) {
                        "quantity", "qty" -> PositionRef(streamAlias)
                        "entry_price", "avg_price", "avg_entry_price" ->
                            StateAccessor(StateSource.POSITION_AVG_PRICE, streamAlias)
                        "pnl" -> StateAccessor(StateSource.POSITION_PNL, streamAlias)
                        "realized_pnl" -> StateAccessor(StateSource.POSITION_REALIZED_PNL, streamAlias)
                        "unrealized_pnl" -> StateAccessor(StateSource.POSITION_UNREALIZED_PNL, streamAlias)
                        "holding_duration" -> StateAccessor(StateSource.POSITION_HOLDING_DURATION, streamAlias)
                        "mfe" -> StateAccessor(StateSource.POSITION_MFE, streamAlias)
                        "mae" -> StateAccessor(StateSource.POSITION_MAE, streamAlias)
                        "count", "open_count" -> StateAccessor(StateSource.POSITION_OPEN_COUNT, streamAlias)
                        "longs", "long_count" -> StateAccessor(StateSource.POSITION_LONG_COUNT, streamAlias)
                        "shorts", "short_count" -> StateAccessor(StateSource.POSITION_SHORT_COUNT, streamAlias)
                        "gross" -> StateAccessor(StateSource.POSITION_GROSS, streamAlias)
                        "trades_today" -> StateAccessor(StateSource.POSITION_TRADES_TODAY, streamAlias)
                        "last_trade_at" -> StateAccessor(StateSource.POSITION_LAST_TRADE_AT, streamAlias)
                        else -> {
                            cursor.errors += ParseError(t.line, t.col, "unknown POSITION accessor: $accessor")
                            PositionRef(streamAlias)
                        }
                    }
                } else {
                    PositionRef(streamAlias)
                }
            }
            TokenKind.POSITION_AVG_PRICE -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after POSITION_AVG_PRICE")
                StateAccessor(StateSource.POSITION_AVG_PRICE, cursor.expectFieldName().lexeme)
            }
            TokenKind.OPEN_ORDERS -> {
                cursor.advance()
                cursor.expect(TokenKind.DOT, "expected '.' after OPEN_ORDERS")
                StateAccessor(StateSource.OPEN_ORDERS, cursor.expectFieldName().lexeme)
            }
            TokenKind.SYMBOL -> {
                cursor.advance()
                Ref("__SYMBOL__")
            }
            TokenKind.NOW -> {
                cursor.advance()
                if (cursor.peek().kind == TokenKind.DOT) {
                    cursor.advance()
                    // NOW.<field>. `WEEKDAY` is also a SCHEDULE token (#77), so we
                    // accept either an IDENT or that specific keyword here and read
                    // the lexeme — keeps `NOW.weekday` working as a field access.
                    val fieldTok =
                        when (cursor.peek().kind) {
                            // WEEKDAY and DAY are also SCHEDULE keywords; accept them here and read
                            // the lexeme so `NOW.weekday` / `NOW.day` work as field accesses.
                            TokenKind.IDENT, TokenKind.WEEKDAY, TokenKind.DAY -> cursor.advance()
                            else -> cursor.expect(TokenKind.IDENT, "expected NOW field name")
                        }
                    val field =
                        when (fieldTok.lexeme.uppercase()) {
                            "HOUR_UTC" -> NowField.HOUR_UTC
                            "MINUTE_UTC" -> NowField.MINUTE_UTC
                            "WEEKDAY" -> NowField.WEEKDAY
                            "MONTH" -> NowField.MONTH
                            "DAY" -> NowField.DAY
                            "DAYS_IN_MONTH" -> NowField.DAYS_IN_MONTH
                            "DATE_UTC" -> NowField.DATE_UTC
                            "EPOCH_MS" -> NowField.EPOCH_MS
                            else -> {
                                cursor.errors +=
                                    ParseError(fieldTok.line, fieldTok.col, "unknown NOW field: ${fieldTok.lexeme}")
                                NowField.EPOCH_MS
                            }
                        }
                    NowAccessor(field)
                } else {
                    NowAccessor(NowField.EPOCH_MS)
                }
            }
            // LOG and FLOOR are also reserved action/order keywords. In expression
            // position their parenthesized forms bind to registered math functions.
            TokenKind.IDENT, TokenKind.OPEN, TokenKind.CLOSE, TokenKind.LOG, TokenKind.FLOOR -> {
                if ((scope.inStackLayerAt || scope.inOtoChildPrice) &&
                    t.kind == TokenKind.IDENT &&
                    t.lexeme == "entry"
                ) {
                    cursor.advance()
                    return StackEntryRef
                }
                val name = cursor.advance().lexeme
                when {
                    cursor.match(TokenKind.LPAREN) -> {
                        val args = mutableListOf<ExprAst>()
                        if (cursor.peek().kind != TokenKind.RPAREN) {
                            args.add(parseExpr())
                            while (cursor.match(TokenKind.COMMA)) args.add(parseExpr())
                        }
                        cursor.expect(TokenKind.RPAREN, "expected ')' after arguments")
                        when {
                            // CALENDAR_WINDOW is a clock-reading boolean primitive (like NOW.*),
                            // not a pure numeric function or an indicator — it gets its own node.
                            name.equals("CALENDAR_WINDOW", ignoreCase = true) ->
                                buildCalendarWindow(args, t)
                            name.equals("SESSION_WINDOW", ignoreCase = true) ->
                                buildSessionWindow(args, t)
                            name.equals("LAST_TRADING_DAY_OF_MONTH", ignoreCase = true) ->
                                buildLastTradingDayOfMonth(args, t)
                            // Rolling shorthand (#1130): avg(x, N) is mean(x) SINCE T-N, and
                            // count(cond, N) counts the last N bars where cond held.
                            name.equals("AVG", ignoreCase = true) -> rollingShorthand(AggFn.MEAN, name, args, t)
                            name.equals("COUNT", ignoreCase = true) -> rollingShorthand(AggFn.SUM, name, args, t)
                            // Scalar math functions (abs, sqrt, log, exp, pow, …) route through
                            // FuncCall — pure functions on numeric values, no warmup or per-bar state.
                            // Everything else stays IndicatorCall for the indicator-binding path.
                            com.qkt.dsl.stdlib.FuncRegistry
                                .has(name.uppercase()) ->
                                FuncCall(name.uppercase(), args)
                            else -> IndicatorCall(name, args)
                        }
                    }
                    cursor.match(TokenKind.DOT) -> {
                        val field = cursor.expectFieldName().lexeme
                        barOffset(StreamFieldRef(name, field))
                    }
                    cursor.match(TokenKind.AT_SIGN) -> Ref(name, parseSnapshotKind())
                    else -> Ref(name)
                }
            }
            TokenKind.LPAREN -> {
                cursor.advance()
                val e = parseExpr()
                cursor.expect(TokenKind.RPAREN, "expected ')'")
                e
            }
            else -> cursor.error("expected expression, got '${t.lexeme}'")
        }
    }

    /**
     * Build a [CalendarWindow] from a `CALENDAR_WINDOW(startMonth, startDay, endMonth, endDay)`
     * call. All four arguments must be integer literals; month must be 1-12 and day 1-31.
     * Violations are recorded as parse errors so the strategy fails to compile rather than
     * silently misbehaving. [at] is the call token, used for error position.
     */
    private fun buildCalendarWindow(
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        val ints =
            args.map { a ->
                (a as? NumLit)?.value?.let { if (it.stripTrailingZeros().scale() <= 0) it.toInt() else null }
            }
        if (args.size != 4 || ints.any { it == null }) {
            cursor.errors +=
                ParseError(
                    at.line,
                    at.col,
                    "CALENDAR_WINDOW expects 4 integer literals: startMonth, startDay, endMonth, endDay",
                )
            return CalendarWindow(1, 1, 1, 1)
        }
        val (sm, sd, em, ed) = ints.map { it!! }
        if (sm !in 1..12 || em !in 1..12 || sd !in 1..31 || ed !in 1..31) {
            cursor.errors += ParseError(at.line, at.col, "CALENDAR_WINDOW month must be 1-12 and day 1-31")
        }
        return CalendarWindow(sm, sd, em, ed)
    }

    /**
     * Build a [SessionWindow] from a `SESSION_WINDOW(startHour, startMinute, endHour, endMinute)`
     * call. All four arguments must be integer literals; hour must be 0-23 and minute 0-59.
     * Violations are recorded as parse errors so the strategy fails to compile rather than
     * silently misbehaving. [at] is the call token, used for error position.
     */
    private fun buildSessionWindow(
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        val ints =
            args.map { a ->
                (a as? NumLit)?.value?.let { if (it.stripTrailingZeros().scale() <= 0) it.toInt() else null }
            }
        if (args.size != 4 || ints.any { it == null }) {
            cursor.errors +=
                ParseError(
                    at.line,
                    at.col,
                    "SESSION_WINDOW expects 4 integer literals: startHour, startMinute, endHour, endMinute",
                )
            return SessionWindow(0, 0, 0, 0)
        }
        val (sh, sm, eh, em) = ints.map { it!! }
        if (sh !in 0..23 || eh !in 0..23 || sm !in 0..59 || em !in 0..59) {
            cursor.errors += ParseError(at.line, at.col, "SESSION_WINDOW hour must be 0-23 and minute 0-59")
        }
        return SessionWindow(sh, sm, eh, em)
    }

    /**
     * Build a [LastTradingDayOfMonth] from a `LAST_TRADING_DAY_OF_MONTH()` call. The predicate
     * takes no arguments; any argument is a parse error. [at] is the call token, for error position.
     */
    private fun buildLastTradingDayOfMonth(
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        if (args.isNotEmpty()) {
            cursor.errors += ParseError(at.line, at.col, "LAST_TRADING_DAY_OF_MONTH takes no arguments")
        }
        return LastTradingDayOfMonth
    }

    private fun parseAggregate(): ExprAst {
        val fnTok = cursor.advance()
        val fn =
            when (fnTok.kind) {
                TokenKind.MAX -> AggFn.MAX
                TokenKind.MIN -> AggFn.MIN
                TokenKind.MEAN -> AggFn.MEAN
                TokenKind.SUM -> AggFn.SUM
                else -> cursor.error("unreachable")
            }
        cursor.expect(TokenKind.LPAREN, "expected '(' after ${fnTok.lexeme}")
        val series = parseExpr()
        // `sum(x, N)` / `mean(x, N)` is shorthand for `sum(x) SINCE T-N` (#1130).
        if (cursor.match(TokenKind.COMMA)) {
            val window = rollingWindowArg(fnTok.lexeme)
            cursor.expect(TokenKind.RPAREN, "expected ')' to close ${fnTok.lexeme}(<expr>, N)")
            return Aggregate(fn, series, window)
        }
        cursor.expect(TokenKind.RPAREN, "expected ')' to close aggregate args")
        cursor.expect(TokenKind.SINCE, "expected SINCE after aggregate")
        val window = parseWindow()
        return Aggregate(fn, series, window)
    }

    /** The `N` of a rolling shorthand: a positive integer literal, the same rule as `T-N`. */
    private fun rollingWindowArg(fnName: String): SinceTPast {
        val tok = cursor.expect(TokenKind.NUMBER, "expected a positive integer window after $fnName(<expr>,")
        val n = tok.lexeme.toIntOrNull()
        if (n == null ||
            n <= 0
        ) {
            cursor.error("$fnName(<expr>, N) window must be a positive integer, got '${tok.lexeme}'")
        }
        return SinceTPast(n)
    }

    private fun rollingShorthand(
        fn: AggFn,
        name: String,
        args: List<ExprAst>,
        at: Token,
    ): ExprAst {
        if (args.size != 2) {
            cursor.errors += ParseError(at.line, at.col, "${name.lowercase()} expects (<expr>, N)")
            return NumLit(java.math.BigDecimal.ZERO)
        }
        val n = (args[1] as? NumLit)?.value
        val window = n?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 }?.toInt()
        if (window == null) {
            cursor.errors +=
                ParseError(at.line, at.col, "${name.lowercase()}(<expr>, N) window must be a positive integer")
            return NumLit(java.math.BigDecimal.ZERO)
        }
        val series =
            if (fn == AggFn.SUM) {
                CaseWhen(listOf(args[0] to NumLit(java.math.BigDecimal.ONE)), NumLit(java.math.BigDecimal.ZERO))
            } else {
                args[0]
            }
        return Aggregate(fn, series, SinceTPast(window))
    }

    private fun parseAggregateOrFunction(): ExprAst {
        val fnTok = cursor.advance()
        cursor.expect(TokenKind.LPAREN, "expected '(' after ${fnTok.lexeme}")
        val args = mutableListOf(parseExpr())
        while (cursor.match(TokenKind.COMMA)) args.add(parseExpr())
        cursor.expect(TokenKind.RPAREN, "expected ')' after arguments")

        if (cursor.match(TokenKind.SINCE)) {
            if (args.size != 1) {
                cursor.errors +=
                    ParseError(fnTok.line, fnTok.col, "${fnTok.lexeme} aggregate expects exactly one series")
            }
            val fn = if (fnTok.kind == TokenKind.MAX) AggFn.MAX else AggFn.MIN
            return Aggregate(fn, args.first(), parseWindow())
        }
        return FuncCall(fnTok.lexeme.uppercase(), args)
    }

    private fun parseWindow(): Window {
        val t = cursor.peek()
        return when {
            t.kind == TokenKind.OPEN -> {
                cursor.advance()
                SinceOpen
            }
            t.kind == TokenKind.IDENT && t.lexeme.equals("T", ignoreCase = true) -> {
                cursor.advance()
                cursor.expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    cursor.expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: cursor.error("expected positive integer after T-")
                SinceTPast(n)
            }
            else -> cursor.error("expected OPEN or T-N for window, got '${t.lexeme}'")
        }
    }

    /**
     * Optional `[n]` bar-offset suffix on a stream field, e.g. `btc.close[20]` is the close
     * 20 bars ago. `[0]` is the current bar (the bare field); `[n>0]` compiles to the `lag`
     * indicator, so it carries lag's `n + 1` bar warmup. A non-integer or negative offset is a
     * parse error.
     */
    private fun barOffset(base: ExprAst): ExprAst {
        if (cursor.peek().kind != TokenKind.LBRACKET) return base
        val open = cursor.advance()
        val nTok = cursor.expect(TokenKind.NUMBER, "expected an integer bar offset inside [ ]")
        cursor.expect(TokenKind.RBRACKET, "expected ']' to close the bar offset")
        val n = nTok.lexeme.toIntOrNull()
        if (n == null || n < 0) {
            cursor.errors +=
                ParseError(open.line, open.col, "bar offset must be a non-negative integer: ${nTok.lexeme}")
            return base
        }
        return if (n == 0) base else IndicatorCall("LAG", listOf(base, NumLit(java.math.BigDecimal(n))))
    }

    private fun parseSnapshotKind(): SnapshotKind {
        val t = cursor.peek()
        return when {
            t.kind == TokenKind.BUY -> {
                cursor.advance()
                SnapshotBuy
            }
            t.kind == TokenKind.SELL -> {
                cursor.advance()
                SnapshotSell
            }
            t.kind == TokenKind.OPEN -> {
                cursor.advance()
                SnapshotOpen
            }
            t.kind == TokenKind.IDENT && t.lexeme.equals("T", ignoreCase = true) -> {
                cursor.advance()
                cursor.expect(TokenKind.MINUS, "expected '-' after T")
                val n =
                    cursor.expect(TokenKind.NUMBER, "expected positive integer after T-").lexeme.toIntOrNull()
                        ?: cursor.error("expected positive integer after T-")
                SnapshotTPast(n)
            }
            else -> cursor.error("expected snapshot kind (buy/sell/open/T-N), got '${t.lexeme}'")
        }
    }

    internal fun parseOrderType(): OrderTypeAst =
        when (cursor.peek().kind) {
            TokenKind.MARKET -> {
                cursor.advance()
                Market
            }
            TokenKind.LIMIT -> {
                cursor.advance()
                if (scope.inExitHook && cursor.peek().kind in setOf(TokenKind.WITH, TokenKind.AGAINST)) {
                    ExitRelativeLimit(parseDirRel())
                } else {
                    cursor.expect(TokenKind.AT, "expected AT after LIMIT")
                    Limit(parseExpr())
                }
            }
            TokenKind.STOP -> {
                cursor.advance()
                if (scope.inExitHook && cursor.peek().kind in setOf(TokenKind.WITH, TokenKind.AGAINST)) {
                    ExitRelativeStop(parseDirRel())
                } else {
                    cursor.expect(TokenKind.AT, "expected AT after STOP")
                    val stopPrice = parseExpr()
                    if (cursor.peek().kind == TokenKind.LIMIT) {
                        cursor.advance()
                        cursor.expect(TokenKind.AT, "expected AT after LIMIT")
                        StopLimit(stopPrice, parseExpr())
                    } else {
                        Stop(stopPrice)
                    }
                }
            }
            TokenKind.TRAILING -> {
                cursor.advance()
                when (cursor.peek().kind) {
                    TokenKind.BY -> {
                        cursor.advance()
                        TrailingBy(parseExpr())
                    }
                    TokenKind.PCT -> {
                        cursor.advance()
                        TrailingPct(parseExpr())
                    }
                    else -> cursor.error("expected BY or PCT after TRAILING, got '${cursor.peek().lexeme}'")
                }
            }
            else -> cursor.error("expected order type (MARKET/LIMIT/STOP/TRAILING), got '${cursor.peek().lexeme}'")
        }

    internal fun parseTif(): TifAst =
        when (cursor.peek().kind) {
            TokenKind.GTC -> {
                cursor.advance()
                Gtc
            }
            TokenKind.IOC -> {
                cursor.advance()
                Ioc
            }
            TokenKind.FOK -> {
                cursor.advance()
                Fok
            }
            TokenKind.DAY -> {
                cursor.advance()
                Day
            }
            TokenKind.GTD -> {
                cursor.advance()
                cursor.match(TokenKind.UNTIL)
                Gtd(parseExpr())
            }
            else -> cursor.error("expected TIF (GTC/IOC/FOK/DAY/GTD), got '${cursor.peek().lexeme}'")
        }

    internal fun parseChildPrice(): ChildPriceAst =
        when (cursor.peek().kind) {
            TokenKind.AT -> {
                cursor.advance()
                ChildAt(parseExpr())
            }
            TokenKind.BY -> {
                cursor.advance()
                val distance = parseExpr()
                when {
                    cursor.match(TokenKind.PCT) -> ChildPct(distance)
                    cursor.peek().kind == TokenKind.STEP ->
                        ChildBy(
                            distance = distance,
                            ratchet = parseSteppedStop(),
                        )
                    cursor.match(TokenKind.TIGHTEN) ->
                        ChildBy(
                            distance = distance,
                            ratchet = parseTimeTighten(),
                        )
                    else -> ChildBy(distance)
                }
            }
            TokenKind.PCT -> {
                cursor.advance()
                ChildPct(parseExpr())
            }
            TokenKind.RR -> {
                cursor.advance()
                ChildRr(parseExpr())
            }
            TokenKind.TRAILING -> {
                cursor.advance()
                val distance = parseExpr()
                cursor.expect(TokenKind.AFTER, "expected AFTER after TRAILING <distance>")
                cursor.expect(TokenKind.MFE, "expected MFE after AFTER")
                cursor.expect(TokenKind.GE, "expected '>=' after MFE")
                val threshold = parseExpr()
                ChildArmedTrail(distance, threshold)
            }
            else -> cursor.error("expected child price (AT/BY/PCT/RR/TRAILING), got '${cursor.peek().lexeme}'")
        }

    private fun parseSteppedStop(): com.qkt.dsl.ast.SteppedStopAst {
        val steps = mutableListOf<com.qkt.dsl.ast.StopStepAst>()
        while (cursor.match(TokenKind.STEP)) {
            cursor.expect(TokenKind.TO, "expected TO after STEP")
            val target = cursor.expect(TokenKind.IDENT, "expected BREAKEVEN or ENTRY after STEP TO")
            if (!target.lexeme.equals("BREAKEVEN", ignoreCase = true) &&
                !target.lexeme.equals("ENTRY", ignoreCase = true)
            ) {
                cursor.error("expected BREAKEVEN or ENTRY after STEP TO")
            }
            val profitDistance =
                if (cursor.match(TokenKind.PLUS)) {
                    parseExpr()
                } else {
                    NumLit(BigDecimal.ZERO)
                }
            cursor.expect(TokenKind.AFTER, "expected AFTER after step target")
            cursor.expect(TokenKind.MFE, "expected MFE after AFTER")
            cursor.expect(TokenKind.GE, "expected '>=' after MFE")
            steps +=
                StopStepAst(
                    mfeThreshold = parseExpr(),
                    profitDistance = profitDistance,
                )
        }
        return SteppedStopAst(steps)
    }

    private fun parseTimeTighten(): TimeTightenAst {
        cursor.expect(TokenKind.BY, "expected BY after TIGHTEN")
        val tightenBy = parseExpr()
        cursor.expect(TokenKind.EVERY, "expected EVERY after TIGHTEN BY <distance>")
        val interval = parseDuration()
        cursor.expect(TokenKind.FLOOR, "expected FLOOR after tightening interval")
        return TimeTightenAst(
            tightenBy = tightenBy,
            interval = interval,
            floorDistance = parseExpr(),
        )
    }

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
        val cond = parseExpr()
        cursor.expect(TokenKind.THEN, "expected THEN after WHEN condition")
        val first = parseAction()
        if (cursor.peek().kind != TokenKind.SEMICOLON) return WhenThen(cond, first)
        val actions = mutableListOf(first)
        while (cursor.match(TokenKind.SEMICOLON)) {
            if (!isActionStart(cursor.peek().kind)) break
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

    internal fun parseAction(): ActionAst =
        when (cursor.peek().kind) {
            TokenKind.BUY -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after BUY").lexeme
                Buy(stream, parseActionOpts())
            }
            TokenKind.SELL -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after SELL").lexeme
                Sell(stream, parseActionOpts())
            }
            TokenKind.CLOSE -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after CLOSE").lexeme
                Close(stream)
            }
            TokenKind.CLOSE_ALL -> {
                cursor.advance()
                CloseAll
            }
            TokenKind.FLATTEN -> {
                cursor.advance()
                CloseAll
            }
            TokenKind.RESIZE -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after RESIZE").lexeme
                cursor.expect(TokenKind.TO, "expected TO after RESIZE stream")
                val target = parseSizing()
                val minStep = if (cursor.match(TokenKind.MIN_STEP)) parseExpr() else null
                Resize(stream, target, minStep)
            }
            TokenKind.CANCEL -> {
                cursor.advance()
                val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after CANCEL").lexeme
                Cancel(stream)
            }
            TokenKind.CANCEL_ALL -> {
                cursor.advance()
                CancelAll
            }
            TokenKind.LOG -> parseLogAction()
            TokenKind.OCO_ENTRY -> parseOcoEntry()
            TokenKind.LATCH -> parseLatch()
            else -> cursor.error("expected action keyword, got '${cursor.peek().lexeme}'")
        }

    private fun parseOcoEntry(): ActionAst {
        cursor.expect(TokenKind.OCO_ENTRY, "expected OCO_ENTRY")
        cursor.expect(TokenKind.LBRACE, "expected '{' after OCO_ENTRY")
        val leg1 = parseAction()
        if (leg1 !is Buy && leg1 !is Sell) {
            cursor.error("OCO_ENTRY legs must be BUY or SELL, got ${leg1::class.simpleName}")
        }
        cursor.expect(TokenKind.COMMA, "expected ',' between OCO_ENTRY legs")
        val leg2 = parseAction()
        if (leg2 !is Buy && leg2 !is Sell) {
            cursor.error("OCO_ENTRY legs must be BUY or SELL, got ${leg2::class.simpleName}")
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close OCO_ENTRY (exactly two legs)")
        return OcoEntry(leg1, leg2)
    }

    private fun parseLatch(): ActionAst {
        cursor.expect(TokenKind.LATCH, "expected LATCH")
        val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after LATCH").lexeme
        cursor.expect(TokenKind.OFFSET, "expected OFFSET after LATCH stream")
        val offset = parseExpr()
        val reference =
            if (cursor.match(TokenKind.FROM)) {
                parseExpr()
            } else {
                null
            }
        cursor.expect(TokenKind.ARM, "expected ARM <duration> in LATCH")
        val armWindow = parseDuration()
        val name =
            if (cursor.match(TokenKind.AS)) {
                cursor.expect(TokenKind.IDENT, "expected name after AS").lexeme
            } else {
                null
            }
        val confirm =
            if (cursor.match(TokenKind.CONFIRM)) {
                parseLatchConfirm()
            } else {
                LatchFirstTick
            }
        cursor.expect(TokenKind.LBRACE, "expected '{' to open LATCH block")
        val entries = mutableListOf(parseLatchEntry())

        while (cursor.match(TokenKind.SEMICOLON)) {
            entries.add(parseLatchEntry())
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close LATCH block")
        return Latch(stream, BreakOffset(reference, offset), armWindow, name, entries, confirm)
    }

    private fun parseLatchConfirm(): LatchConfirm =
        when (cursor.peek().kind) {
            TokenKind.CLOSE_BEYOND -> {
                cursor.advance()
                LatchCloseBeyond
            }
            TokenKind.TIME_IN_BREACH -> {
                cursor.advance()
                LatchTimeInBreach(parseDuration())
            }
            TokenKind.RETEST_HOLD -> {
                cursor.advance()
                val distance = parseExpr()
                cursor.expect(TokenKind.WITHIN, "expected WITHIN after RETEST_HOLD distance")
                LatchRetestHold(distance, parseDuration())
            }
            else -> cursor.error("expected CLOSE_BEYOND, TIME_IN_BREACH, or RETEST_HOLD after CONFIRM")
        }

    private fun parseLatchEntry(): LatchEntry {
        cursor.expect(TokenKind.ENTER, "expected ENTER in LATCH block")
        val entryStream =
            if (cursor.match(TokenKind.ON)) {
                cursor.expect(TokenKind.IDENT, "expected stream alias after ENTER ON").lexeme
            } else {
                null
            }
        val order = parseLatchOrder()
        var bracket: LatchBracket? = null
        var sizing: SizingAst? = null
        var expire: DurationAst? = null
        loop@ while (true) {
            when (cursor.peek().kind) {
                TokenKind.BRACKET -> {
                    cursor.advance()
                    bracket = parseLatchBracket()
                }
                TokenKind.SIZING -> {
                    cursor.advance()
                    sizing = parseSizing()
                }
                TokenKind.EXPIRE -> {
                    cursor.advance()
                    expire = parseDuration()
                }
                else -> break@loop
            }
        }
        return LatchEntry(order, bracket, sizing, expire, entryStream)
    }

    private fun parseLatchOrder(): LatchOrder =
        when (cursor.peek().kind) {
            TokenKind.MARKET -> {
                cursor.advance()
                LatchMarket
            }
            TokenKind.LIMIT -> {
                cursor.advance()
                LatchLimit(parseDirRel())
            }
            TokenKind.STOP -> {
                cursor.advance()
                LatchStop(parseDirRel())
            }
            else -> cursor.error("expected MARKET/LIMIT/STOP after ENTER, got '${cursor.peek().lexeme}'")
        }

    private fun parseDirRel(): DirRel {
        val sense =
            when (cursor.peek().kind) {
                TokenKind.WITH -> DirSense.WITH
                TokenKind.AGAINST -> DirSense.AGAINST
                TokenKind.RETRACE -> DirSense.AGAINST
                else -> cursor.error("expected WITH/AGAINST/RETRACE, got '${cursor.peek().lexeme}'")
            }
        cursor.advance()
        return DirRel(sense, parseExpr())
    }

    private fun parseLatchBracket(): LatchBracket {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open BRACKET block")
        var stopLoss: DirRel? = null
        var takeProfit: DirRel? = null
        do {
            // Accept both `STOP LOSS` / `TAKE PROFIT` and the single-token `STOP_LOSS` / `TAKE_PROFIT`.
            when (val tok = cursor.peek().kind) {
                TokenKind.STOP, TokenKind.STOP_LOSS -> {
                    cursor.advance()
                    if (tok == TokenKind.STOP) cursor.expect(TokenKind.LOSS, "expected LOSS after STOP")
                    stopLoss = parseDirRel()
                }
                TokenKind.TAKE, TokenKind.TAKE_PROFIT -> {
                    cursor.advance()
                    if (tok == TokenKind.TAKE) cursor.expect(TokenKind.PROFIT, "expected PROFIT after TAKE")
                    takeProfit = parseDirRel()
                }
                else -> cursor.error("expected STOP LOSS or TAKE PROFIT in BRACKET, got '${cursor.peek().lexeme}'")
            }
        } while (cursor.match(TokenKind.COMMA))
        cursor.expect(TokenKind.RBRACE, "expected '}' to close BRACKET block")
        return LatchBracket(stopLoss, takeProfit)
    }

    private fun parseLogAction(): Log {
        cursor.expect(TokenKind.LOG, "expected LOG")
        val level =
            when (cursor.peek().kind) {
                TokenKind.WARN -> {
                    cursor.advance()
                    LogLevel.WARN
                }
                TokenKind.ERROR -> {
                    cursor.advance()
                    LogLevel.ERROR
                }
                TokenKind.DEBUG -> {
                    cursor.advance()
                    LogLevel.DEBUG
                }
                else -> LogLevel.INFO
            }
        val message = cursor.expect(TokenKind.STRING, "expected string literal after LOG").lexeme
        val fields = linkedMapOf<String, ExprAst>()
        while (cursor.peek().kind == TokenKind.IDENT && cursor.peekAtOrNull(1)?.kind == TokenKind.EQ) {
            val name = cursor.expect(TokenKind.IDENT, "expected field name").lexeme
            cursor.expect(TokenKind.EQ, "expected '='")
            val expr = parseExpr()
            if (fields.containsKey(name)) {
                cursor.error("duplicate LOG field '$name'")
            }
            fields[name] = expr
        }
        val placeholders = LOG_PLACEHOLDER_REGEX.findAll(message).map { it.groupValues[1] }.toSet()
        val unmatched = placeholders - fields.keys
        if (unmatched.isNotEmpty()) {
            cursor.error("LOG placeholder(s) without matching field: ${unmatched.joinToString()}")
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
        var onFill: List<ActionAst> = emptyList()
        var onStop: List<ActionAst> = emptyList()
        var onTakeProfit: List<ActionAst> = emptyList()
        var onClose: List<ActionAst> = emptyList()
        var times: ExprAst? = null
        loop@ while (true) {
            when (cursor.peek().kind) {
                TokenKind.SIZING -> {
                    cursor.advance()
                    sizing = parseSizing()
                }
                TokenKind.TIMES -> {
                    if (times != null) cursor.error("duplicate TIMES clause")
                    cursor.advance()
                    times = parseExpr()
                }
                TokenKind.ORDER_TYPE -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after ORDER_TYPE")
                    orderType = parseOrderType()
                }
                TokenKind.TIF -> {
                    cursor.advance()
                    tif = parseTif()
                }
                TokenKind.BRACKET -> {
                    cursor.advance()
                    bracket = parseBracket()
                }
                TokenKind.OCO -> {
                    cursor.advance()
                    oco = parseOco()
                }
                TokenKind.STACK -> {
                    cursor.advance()
                    stack = parseStackClause()
                }
                TokenKind.STACK_AT -> {
                    stackAts += parseStackAtClause()
                }
                TokenKind.ON_FILL -> {
                    cursor.advance()
                    onFill = parseOnFill()
                }
                TokenKind.ON_STOP -> {
                    if (onStop.isNotEmpty()) cursor.error("duplicate ON_STOP clause")
                    cursor.advance()
                    onStop = parseExitHook("ON_STOP")
                }
                TokenKind.ON_TP -> {
                    if (onTakeProfit.isNotEmpty()) cursor.error("duplicate ON_TP clause")
                    cursor.advance()
                    onTakeProfit = parseExitHook("ON_TP")
                }
                TokenKind.ON_CLOSE -> {
                    if (onClose.isNotEmpty()) cursor.error("duplicate ON_CLOSE clause")
                    cursor.advance()
                    onClose = parseExitHook("ON_CLOSE")
                }
                else -> break@loop
            }
        }
        val finalStack = stack
        if (sizing != null && finalStack is StackLayers) {
            cursor.error(
                "STACK layer-list cannot be combined with outer SIZING; specify size on each layer or remove the layer list",
            )
        }
        // orderType stays null here so DEFAULTS ORDER_TYPE can fill it during the
        // defaults merge; ActionCompiler applies the Market fallback after the merge.
        return ActionOpts(
            sizing,
            orderType,
            tif,
            bracket,
            oco,
            finalStack,
            stackAts,
            onFill,
            ExitHooksAst(onStop, onTakeProfit, onClose),
            times = times,
        )
    }

    /**
     * Parse an OTO child block: `ON_FILL { <BUY|SELL …> [; <BUY|SELL …>]* }`.
     *
     * Each child is a normal BUY/SELL action, so it reuses the full action grammar (sizing,
     * order type). Inside the block, `entry` resolves to the parent fill price, letting a child
     * price itself relative to where the parent filled (e.g. `LIMIT AT entry - 10`).
     */
    private fun parseOnFill(): List<ActionAst> {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open ON_FILL block")
        val children = mutableListOf<ActionAst>()
        val prev = scope.inOtoChildPrice
        scope.inOtoChildPrice = true
        try {
            children.add(parseAction())
            while (cursor.peek().kind == TokenKind.SEMICOLON) {
                cursor.advance()
                if (cursor.peek().kind == TokenKind.RBRACE) break
                children.add(parseAction())
            }
        } finally {
            scope.inOtoChildPrice = prev
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close ON_FILL block")
        return children
    }

    /**
     * Parse an exit hook block. Children reuse BUY/SELL parsing, while the compiler
     * enforces the v1 action and nesting constraints.
     */
    private fun parseExitHook(name: String): List<ActionAst> {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open $name block")
        val children = mutableListOf<ActionAst>()
        val previous = scope.inExitHook
        scope.inExitHook = true
        try {
            if (cursor.peek().kind == TokenKind.RBRACE) cursor.error("$name block must contain at least one action")
            children.add(parseAction())
            while (cursor.match(TokenKind.SEMICOLON)) {
                if (cursor.peek().kind == TokenKind.RBRACE) break
                children.add(parseAction())
            }
        } finally {
            scope.inExitHook = previous
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close $name block")
        return children
    }

    /**
     * Phase 27: `STACK_AT MFE >= <expr> WITHIN <duration> SIZING <sizing> BRACKET { ... }`.
     * Phase 38: `STACK_AT MAE >= <expr> RECOVER <expr> WITHIN <duration> ...`.
     *
     * The clause attaches to its parent BUY/SELL action. The stack engine fires the stack
     * when the parent leg's MFE crosses the threshold within the duration window.
     */
    private fun parseStackAtClause(): StackAtClause {
        cursor.expect(TokenKind.STACK_AT, "expected STACK_AT")
        val trigger = cursor.peek().kind
        when (trigger) {
            TokenKind.MFE, TokenKind.MAE -> cursor.advance()
            else -> {
                cursor.errors += ParseError(cursor.peek().line, cursor.peek().col, "expected MFE or MAE after STACK_AT")
                cursor.advance()
            }
        }
        cursor.expect(TokenKind.GE, "expected '>=' after MFE/MAE in STACK_AT")
        val threshold = parseExpr()
        val recoverDistance =
            if (trigger == TokenKind.MAE) {
                cursor.expect(TokenKind.RECOVER, "expected RECOVER after MAE threshold in STACK_AT")
                parseExpr()
            } else {
                null
            }
        cursor.expect(TokenKind.WITHIN, "expected WITHIN after STACK_AT threshold")
        val duration = parseDuration()
        cursor.expect(TokenKind.SIZING, "expected SIZING in STACK_AT clause")
        val sizing = parseSizing()
        cursor.expect(TokenKind.BRACKET, "expected BRACKET in STACK_AT clause")
        val bracket = parseBracket()
        return StackAtClause(
            mfeThreshold = threshold,
            withinDuration = duration,
            sizing = sizing,
            bracket = bracket,
            maeRecoverDistance = recoverDistance,
        )
    }

    internal fun parseStackClause(): StackAst {
        // STACK <count> SPACING <expr> [ABOVE|BELOW] [WITHIN <duration>]
        // STACK [ <layers> ] [WITHIN <duration>]   (added in Task 5)
        return if (cursor.peek().kind == TokenKind.LBRACKET) {
            parseStackLayers()
        } else {
            parseStackSpacing()
        }
    }

    internal fun parseStackSpacing(): StackSpacing {
        val countTok = cursor.expect(TokenKind.NUMBER, "expected count after STACK")
        val count =
            countTok.lexeme.toIntOrNull()
                ?: cursor.error("STACK count must be a positive integer, got '${countTok.lexeme}'")
        if (count < 1) cursor.error("STACK count must be >= 1, got $count")
        cursor.expect(TokenKind.SPACING, "expected SPACING after STACK count")
        val spacing = parseExpr()
        val direction =
            when (cursor.peek().kind) {
                TokenKind.ABOVE -> {
                    cursor.advance()
                    StackDirection.ABOVE
                }
                TokenKind.BELOW -> {
                    cursor.advance()
                    StackDirection.BELOW
                }
                else -> StackDirection.TRADE_DIRECTION
            }
        val within = if (cursor.peek().kind == TokenKind.WITHIN) parseWithin() else null
        return StackSpacing(count, spacing, direction, within)
    }

    internal fun parseStackLayers(): StackLayers {
        cursor.expect(TokenKind.LBRACKET, "expected '[' to open layer list")
        val layers = mutableListOf<StackLayer>()
        if (cursor.peek().kind == TokenKind.RBRACKET) {
            cursor.error("STACK layer list must not be empty")
        }
        layers.add(parseLayer(isFirst = true))
        while (cursor.peek().kind == TokenKind.COMMA) {
            cursor.advance()
            if (cursor.peek().kind == TokenKind.RBRACKET) break
            layers.add(parseLayer(isFirst = false))
        }
        cursor.expect(TokenKind.RBRACKET, "expected ']' to close layer list")
        val within = if (cursor.peek().kind == TokenKind.WITHIN) parseWithin() else null
        return StackLayers(layers, within)
    }

    internal fun parseLayer(isFirst: Boolean): StackLayer {
        val sizing = parseSizing()
        scope.inStackLayerAt = true
        try {
            val orderType: OrderTypeAst? =
                when (cursor.peek().kind) {
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
                if (cursor.peek().kind == TokenKind.AT) {
                    if (priceFromOrderType != null) {
                        cursor.error(
                            "STACK layer with LIMIT/STOP/STOPLIMIT cannot have a separate AT clause; " +
                                "the order type's price is the trigger",
                        )
                    }
                    cursor.advance()
                    parseExpr()
                } else {
                    null
                }
            val at: ExprAst? = priceFromOrderType ?: explicitAt
            if (!isFirst && at == null) {
                cursor.error("STACK layers after the first must have a trigger (via AT or LIMIT/STOP price)")
            }
            return StackLayer(sizing, orderType, at)
        } finally {
            scope.inStackLayerAt = false
        }
    }

    internal fun parseWithin(): DurationAst {
        cursor.expect(TokenKind.WITHIN, "expected WITHIN")
        return parseDuration()
    }

    internal fun parseDuration(): DurationAst {
        val tok = cursor.expect(TokenKind.DURATION, "expected duration literal (e.g., 1h, 30m)")
        val lex = tok.lexeme
        val n =
            lex.dropLast(1).toLongOrNull()
                ?: cursor.error("invalid duration literal '$lex'")
        val unit = lex.last()
        val millis =
            when (unit) {
                's' -> n * 1_000L
                'm' -> n * 60_000L
                'h' -> n * 3_600_000L
                'd' -> n * 86_400_000L
                else -> cursor.error("unknown duration unit '$unit' in '$lex'")
            }
        return DurationAst(millis)
    }

    internal fun parseDefaults(): DefaultsBlock {
        cursor.expect(TokenKind.DEFAULTS, "expected DEFAULTS")
        cursor.expect(TokenKind.LBRACE, "expected '{' after DEFAULTS")
        var sizing: SizingAst? = null
        var orderType: OrderTypeAst? = null
        var tif: TifAst? = null
        var stopLoss: ChildPriceAst? = null
        var takeProfit: ChildPriceAst? = null
        var trailing: OrderTypeAst? = null
        while (cursor.peek().kind != TokenKind.RBRACE && cursor.peek().kind != TokenKind.EOF) {
            when (cursor.peek().kind) {
                TokenKind.SIZING -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after SIZING in DEFAULTS")
                    sizing = parseSizing()
                }
                TokenKind.STOP_LOSS -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after STOP_LOSS in DEFAULTS")
                    stopLoss = parseChildPrice()
                }
                TokenKind.TAKE_PROFIT -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after TAKE_PROFIT in DEFAULTS")
                    takeProfit = parseChildPrice()
                }
                TokenKind.TIF -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after TIF in DEFAULTS")
                    tif = parseTif()
                }
                TokenKind.ORDER_TYPE -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after ORDER_TYPE in DEFAULTS")
                    orderType = parseOrderType()
                }
                TokenKind.TRAILING -> {
                    cursor.advance()
                    cursor.expect(TokenKind.EQ, "expected '=' after TRAILING in DEFAULTS")
                    trailing = parseOrderType()
                }
                else -> cursor.error("expected DEFAULTS clause keyword, got '${cursor.peek().lexeme}'")
            }
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close DEFAULTS")
        return DefaultsBlock(sizing, orderType, tif, stopLoss, takeProfit, trailing)
    }

    internal fun parseBracket(): BracketAst {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open BRACKET block")
        var stopLoss: ChildPriceAst? = null
        var takeProfit: ChildPriceAst? = null
        do {
            // Accept both the two-word `STOP LOSS` / `TAKE PROFIT` and the single-token
            // `STOP_LOSS` / `TAKE_PROFIT` spellings (the latter is what DEFAULTS uses).
            when (val tok = cursor.peek().kind) {
                TokenKind.STOP, TokenKind.STOP_LOSS -> {
                    cursor.advance()
                    if (tok == TokenKind.STOP) cursor.expect(TokenKind.LOSS, "expected LOSS after STOP")
                    stopLoss = parseChildPrice()
                }
                TokenKind.TAKE, TokenKind.TAKE_PROFIT -> {
                    cursor.advance()
                    if (tok == TokenKind.TAKE) cursor.expect(TokenKind.PROFIT, "expected PROFIT after TAKE")
                    if (cursor.peek().kind == TokenKind.TRAILING) {
                        cursor.error(
                            "TAKE PROFIT TRAILING is not supported — TRAILING is stop-only " +
                                "(armed trail). Use TAKE PROFIT AT/BY/PCT/RR.",
                        )
                    }
                    takeProfit = parseChildPrice()
                }
                else -> cursor.error("expected STOP LOSS or TAKE PROFIT in BRACKET, got '${cursor.peek().lexeme}'")
            }
        } while (cursor.match(TokenKind.COMMA))
        cursor.expect(TokenKind.RBRACE, "expected '}' to close BRACKET block")
        return BracketAst(stopLoss, takeProfit)
    }

    internal fun parseOco(): OcoAst {
        cursor.expect(TokenKind.LBRACE, "expected '{' to open OCO block")
        var stop: ChildPriceAst? = null
        var limit: ChildPriceAst? = null
        do {
            when (cursor.peek().kind) {
                TokenKind.STOP -> {
                    cursor.advance()
                    cursor.expect(TokenKind.AT, "expected AT after STOP in OCO")
                    stop = ChildAt(parseExpr())
                }
                TokenKind.LIMIT -> {
                    cursor.advance()
                    cursor.expect(TokenKind.AT, "expected AT after LIMIT in OCO")
                    limit = ChildAt(parseExpr())
                }
                else -> cursor.error("expected STOP AT or LIMIT AT in OCO, got '${cursor.peek().lexeme}'")
            }
        } while (cursor.match(TokenKind.COMMA))
        cursor.expect(TokenKind.RBRACE, "expected '}' to close OCO block")
        val s = stop ?: cursor.error("OCO requires a STOP AT child")
        val l = limit ?: cursor.error("OCO requires a LIMIT AT child")
        return OcoAst(s, l)
    }

    internal fun parseSizing(): SizingAst {
        val k = cursor.peek().kind
        return when (k) {
            TokenKind.RISK -> {
                cursor.advance()
                if (cursor.match(TokenKind.DOLLAR)) {
                    SizeRiskAbs(parseExpr())
                } else {
                    riskFracWithOptionalBookBasis(parseExpr())
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
                val e = parseExpr()
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

    private fun parseCaseWhen(): ExprAst {
        cursor.expect(TokenKind.CASE, "expected CASE")
        val branches = mutableListOf<Pair<ExprAst, ExprAst>>()
        while (cursor.peek().kind == TokenKind.WHEN) {
            cursor.advance()
            val cond = parseExpr()
            cursor.expect(TokenKind.THEN, "expected THEN in CASE branch")
            val body = parseExpr()
            branches.add(cond to body)
        }
        if (branches.isEmpty()) cursor.error("CASE requires at least one WHEN branch")
        val elseExpr =
            if (cursor.match(TokenKind.ELSE)) {
                parseExpr()
            } else {
                cursor.error("CASE requires an ELSE branch")
            }
        cursor.expect(TokenKind.END, "expected END to close CASE")
        return CaseWhen(branches, elseExpr)
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
                    parseDuration().millis
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
        val timeframe = parseTimeframe()
        val windowMs =
            com.qkt.candles.TimeWindow
                .parse(timeframe)
                .durationMs
        if (windowMs < 60_000L) cursor.error("SERIES '$alias' timeframe must be >= 1m, got '$timeframe'")
        return SeriesDecl(alias = alias, source = source, timeframe = timeframe)
    }

    private fun parseSequence(): SequenceDecl {
        cursor.expect(TokenKind.SEQUENCE, "expected SEQUENCE")
        val name = cursor.expect(TokenKind.IDENT, "expected sequence name after SEQUENCE").lexeme
        cursor.expect(TokenKind.ON, "expected ON after SEQUENCE name")
        val stream = cursor.expect(TokenKind.IDENT, "expected stream alias after SEQUENCE ON").lexeme
        cursor.expect(TokenKind.LBRACE, "expected '{' to open SEQUENCE block")
        val stages = mutableListOf<SequenceStageDecl>()
        while (cursor.peek().kind != TokenKind.RBRACE && cursor.peek().kind != TokenKind.EOF) {
            cursor.expect(TokenKind.STAGE, "expected STAGE in SEQUENCE block")
            val stageName = cursor.expect(TokenKind.IDENT, "expected stage name after STAGE").lexeme
            val within = if (cursor.match(TokenKind.WITHIN)) parseDuration() else null
            cursor.expect(TokenKind.COLON, "expected ':' after SEQUENCE stage header")
            stages += SequenceStageDecl(stageName, within, parseExpr())
        }
        cursor.expect(TokenKind.RBRACE, "expected '}' to close SEQUENCE block")
        return SequenceDecl(name, stream, stages)
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
        val timeframe = parseTimeframe()
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
        val timeframe = parseTimeframe()
        return com.qkt.dsl.ast.BasketDecl(
            alias = alias,
            weighting = weighting,
            constituents = constituents.toList(),
            timeframe = timeframe,
        )
    }

    /** Parse an `EVERY` timeframe value: a `DURATION` token (`1h`) or a `<number><unit>` pair. */
    private fun parseTimeframe(): String =
        if (cursor.peek().kind == TokenKind.DURATION) {
            cursor.advance().lexeme
        } else {
            val tfNum = cursor.expect(TokenKind.NUMBER, "expected timeframe count").lexeme
            val tfUnit = cursor.expect(TokenKind.IDENT, "expected timeframe unit (s/m/h/d)").lexeme
            "$tfNum$tfUnit"
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
        cursor.expect(TokenKind.SCHEDULE, "expected SCHEDULE")
        val out = mutableListOf<ScheduleDecl>()
        while (cursor.peek().kind == TokenKind.AT || cursor.peek().kind == TokenKind.EVERY) {
            val triggers = mutableListOf<ScheduleTrigger>()
            if (cursor.peek().kind == TokenKind.AT) {
                cursor.advance() // AT
                val times = mutableListOf<TimeOfDay>()
                times.add(parseTimeOfDay())
                while (cursor.peek().kind == TokenKind.COMMA) {
                    cursor.advance()
                    times.add(parseTimeOfDay())
                }
                val tz = parseTimezone("SCHEDULE AT")
                for (t in times) {
                    triggers.add(ScheduleTrigger.At(time = t, tz = tz))
                }
            } else {
                triggers.add(parseScheduleTrigger())
            }
            cursor.expect(TokenKind.THEN, "expected THEN after SCHEDULE trigger(s)")
            val action = parseAction()
            out.add(ScheduleDecl(triggers = triggers, action = action))
        }
        return out
    }

    /** Parse one non-list trigger: `EVERY HOUR AT :NN`, `EVERY DAY AT ...`, `EVERY WEEKDAY AT ...`. */
    private fun parseScheduleTrigger(): ScheduleTrigger {
        cursor.expect(TokenKind.EVERY, "expected EVERY")
        return when (cursor.peek().kind) {
            TokenKind.HOUR -> {
                cursor.advance()
                cursor.expect(TokenKind.AT, "expected AT after EVERY HOUR")
                cursor.expect(TokenKind.COLON, "expected ':' before minute offset")
                val mTok = cursor.expect(TokenKind.NUMBER, "expected minute 0-59")
                val m = mTok.lexeme.toIntOrNull() ?: cursor.error("expected integer minute, got '${mTok.lexeme}'")
                ScheduleTrigger.EveryHour(minuteOffset = m)
            }
            TokenKind.DAY -> {
                cursor.advance()
                cursor.expect(TokenKind.AT, "expected AT after EVERY DAY")
                val time = parseTimeOfDay()
                val tz = parseTimezone("EVERY DAY")
                ScheduleTrigger.EveryDay(time = time, tz = tz)
            }
            TokenKind.WEEKDAY -> {
                cursor.advance()
                cursor.expect(TokenKind.AT, "expected AT after EVERY WEEKDAY")
                val time = parseTimeOfDay()
                val tz = parseTimezone("EVERY WEEKDAY")
                ScheduleTrigger.EveryWeekday(time = time, tz = tz)
            }
            else -> cursor.error("expected HOUR, DAY, or WEEKDAY after EVERY, got '${cursor.peek().lexeme}'")
        }
    }

    /**
     * Parse the timezone tag that follows a time literal in a `SCHEDULE` trigger.
     * One of `UTC` / `NY` / `LONDON` / `TOKYO` / `SYDNEY` / `CHICAGO` / `BROKER`.
     * [where] is the trigger label used in the error message.
     */
    private fun parseTimezone(where: String): Timezone =
        when (cursor.peek().kind) {
            TokenKind.UTC -> {
                cursor.advance()
                Timezone.UTC
            }
            TokenKind.NY -> {
                cursor.advance()
                Timezone.NY
            }
            TokenKind.LONDON -> {
                cursor.advance()
                Timezone.LONDON
            }
            TokenKind.TOKYO -> {
                cursor.advance()
                Timezone.TOKYO
            }
            TokenKind.SYDNEY -> {
                cursor.advance()
                Timezone.SYDNEY
            }
            TokenKind.CHICAGO -> {
                cursor.advance()
                Timezone.CHICAGO
            }
            TokenKind.BROKER -> {
                cursor.advance()
                Timezone.BROKER
            }
            else ->
                cursor.error(
                    "$where requires an explicit timezone " +
                        "(UTC/NY/LONDON/TOKYO/SYDNEY/CHICAGO/BROKER), got '${cursor.peek().lexeme}'",
                )
        }

    /**
     * Parse `HH:MM` or `HH:MM:SS` into a [TimeOfDay]. Out-of-range values are
     * rejected by [TimeOfDay.init].
     */
    private fun parseTimeOfDay(): TimeOfDay {
        val hourTok = cursor.expect(TokenKind.NUMBER, "expected hour")
        cursor.expect(TokenKind.COLON, "expected ':' after hour")
        val minTok = cursor.expect(TokenKind.NUMBER, "expected minute")
        val second: Int =
            if (cursor.peek().kind == TokenKind.COLON) {
                cursor.advance()
                val secTok = cursor.expect(TokenKind.NUMBER, "expected second")
                secTok.lexeme.toIntOrNull() ?: cursor.error("expected integer second, got '${secTok.lexeme}'")
            } else {
                0
            }
        return TimeOfDay(
            hour = hourTok.lexeme.toIntOrNull() ?: cursor.error("expected integer hour, got '${hourTok.lexeme}'"),
            minute = minTok.lexeme.toIntOrNull() ?: cursor.error("expected integer minute, got '${minTok.lexeme}'"),
            second = second,
        )
    }

    companion object {
        private val LOG_PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")

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
