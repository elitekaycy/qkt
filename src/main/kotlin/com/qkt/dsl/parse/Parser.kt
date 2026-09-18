package com.qkt.dsl.parse

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.OcoAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.TifAst

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
    private val symbolsParser = SymbolsParser(cursor, literalParser)
    private val portfolioParser = PortfolioParser(cursor, literalParser, expressionParser, symbolsParser)
    private val strategyParser =
        StrategyParser(cursor, ruleParser, scheduleParser, declarationParser, defaultsParser, symbolsParser)

    /** Parses a whole file, dispatching on its first keyword to a strategy or a portfolio. */
    fun parseFile(): ParseResult<ParsedFile> =
        when (cursor.peek().kind) {
            TokenKind.STRATEGY ->
                when (val r = strategyParser.parseStrategy()) {
                    is ParseResult.Success -> ParseResult.Success(ParsedFile.StrategyFile(r.value))
                    is ParseResult.Failure -> ParseResult.Failure(r.errors)
                }
            TokenKind.PORTFOLIO ->
                when (val r = portfolioParser.parsePortfolio()) {
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

    /** Parses a file that must be a `STRATEGY`; every recoverable error is reported together. */
    fun parseStrategy(): ParseResult<StrategyAst> = strategyParser.parseStrategy()

    internal fun parsePortfolio(): ParseResult<com.qkt.dsl.ast.PortfolioAst> = portfolioParser.parsePortfolio()

    // Single-clause entry points, used by focused grammar tests.
    internal fun parseAction(): ActionAst = actionParser.parseAction()

    internal fun parseBracket(): BracketAst = bracketParser.parseBracket()

    internal fun parseOco(): OcoAst = bracketParser.parseOco()

    internal fun parseChildPrice(): ChildPriceAst = bracketParser.parseChildPrice()

    internal fun parseOrderType(): OrderTypeAst = orderTypeParser.parseOrderType()

    internal fun parseTif(): TifAst = orderTypeParser.parseTif()

    internal fun parseSizing(): SizingAst = sizingParser.parseSizing()
}
