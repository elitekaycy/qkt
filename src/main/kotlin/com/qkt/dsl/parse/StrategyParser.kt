package com.qkt.dsl.parse

import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.ParamDecl
import com.qkt.dsl.ast.SequenceDecl
import com.qkt.dsl.ast.StrategyAst

/**
 * Parses a `STRATEGY` file: the header, then each top-level block in its fixed order (`DEFAULTS`,
 * `SYMBOLS`, `PARAM`, `LET`, `SCHEDULE`, `SEQUENCE`, `RULES`). A failing block is skipped to the next
 * section keyword so one parse reports every error it can find.
 */
internal class StrategyParser(
    private val cursor: TokenCursor,
    private val ruleParser: RuleParser,
    private val scheduleParser: ScheduleParser,
    private val declarationParser: DeclarationParser,
    private val defaultsParser: DefaultsParser,
    private val symbolsParser: SymbolsParser,
) {
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
                cursor.tryParse { symbolsParser.parseSymbols() } ?: SymbolsBlock(emptyList(), emptyList())
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
}
