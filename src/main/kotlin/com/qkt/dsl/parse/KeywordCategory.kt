package com.qkt.dsl.parse

/**
 * The role each lexer keyword plays in a `.qkt` file, for editor tooling. Every keyword the
 * lexer recognizes (see [Lexer.keywordSpellings]) maps to exactly one category; `ARROW` is the
 * `->` punctuation kind and is not a keyword. Grammars highlight one [textMateScope] per
 * category and Vim links one [vimGroup] per category to a stock highlight group.
 */
enum class KeywordCategory(
    val textMateScope: String,
    val vimGroup: String,
    val vimLink: String,
) {
    SECTION("keyword.control.section.qkt", "qktSection", "PreProc"),
    FLOW("keyword.control.flow.qkt", "qktFlow", "Conditional"),
    ACTION("keyword.other.action.qkt", "qktAction", "Statement"),
    ORDER("keyword.other.order.qkt", "qktOrder", "Keyword"),
    SIZING("keyword.other.sizing.qkt", "qktSizing", "Keyword"),
    BRACKET("keyword.other.bracket.qkt", "qktBracket", "Keyword"),
    STACKING("keyword.other.stacking.qkt", "qktStacking", "Keyword"),
    PORTFOLIO("keyword.other.portfolio.qkt", "qktPortfolio", "Keyword"),
    SESSION("keyword.other.session.qkt", "qktSession", "Keyword"),
    HOOK("keyword.other.hook.qkt", "qktHook", "Keyword"),
    STATE("variable.language.qkt", "qktState", "Identifier"),
    OPERATOR_WORD("keyword.operator.word.qkt", "qktOperatorWord", "Operator"),
    AGGREGATE("support.function.aggregate.qkt", "qktAggregate", "Function"),
    LITERAL("constant.language.boolean.qkt", "qktBoolean", "Boolean"),
    ;

    companion object {
        private val table: Map<String, KeywordCategory> =
            buildMap {
                fun put(
                    category: KeywordCategory,
                    vararg spellings: String,
                ) = spellings.forEach { put(it, category) }
                put(
                    SECTION,
                    "STRATEGY",
                    "VERSION",
                    "DEFAULTS",
                    "SYMBOLS",
                    "SYNCHRONIZE",
                    "BASKET",
                    "SERIES",
                    "SCHEDULE",
                    "SEQUENCE",
                    "LET",
                    "PARAM",
                    "RULES",
                    "PORTFOLIO",
                    "IMPORT",
                    "REGIMES",
                )
                put(FLOW, "WHEN", "THEN", "FOR", "EACH", "IN", "DO", "CASE", "ELSE", "END", "SINCE", "EVERY")
                put(
                    ACTION,
                    "BUY",
                    "SELL",
                    "CLOSE",
                    "CLOSE_ALL",
                    "FLATTEN",
                    "RESIZE",
                    "CANCEL",
                    "CANCEL_ALL",
                    "LOG",
                    "WARN",
                    "ERROR",
                    "DEBUG",
                )
                put(
                    ORDER,
                    "MARKET",
                    "LIMIT",
                    "STOP",
                    "TRAILING",
                    "AT",
                    "BY",
                    "TO",
                    "PCT",
                    "STEP",
                    "TIGHTEN",
                    "FLOOR",
                    "ORDER_TYPE",
                    "STOP_LOSS",
                    "TAKE_PROFIT",
                    "TIF",
                    "GTC",
                    "IOC",
                    "FOK",
                    "DAY",
                    "GTD",
                    "UNTIL",
                )
                put(SIZING, "SIZING", "MIN_STEP", "RISK", "USD", "OF", "EQUITY", "BALANCE")
                put(
                    BRACKET,
                    "BRACKET",
                    "OCO",
                    "OCO_ENTRY",
                    "ON",
                    "TAKE",
                    "PROFIT",
                    "LOSS",
                    "RR",
                    "LATCH",
                    "ENTER",
                    "OFFSET",
                    "ARM",
                    "WITH",
                    "AGAINST",
                    "RETRACE",
                    "FROM",
                    "EXPIRE",
                    "CONFIRM",
                    "CLOSE_BEYOND",
                    "TIME_IN_BREACH",
                    "RETEST_HOLD",
                )
                put(
                    STACKING,
                    "STACK",
                    "STACK_AT",
                    "STAGE",
                    "SPACING",
                    "TIMES",
                    "WITHIN",
                    "AFTER",
                    "MFE",
                    "MAE",
                    "RECOVER",
                )
                put(
                    PORTFOLIO,
                    "AS",
                    "RUN",
                    "HOLD",
                    "CAPITAL",
                    "WEIGHT",
                    "OVERRIDE",
                    "NAME",
                    "STATE",
                    "DEFAULT",
                    "ALLOCATE",
                    "METHOD",
                    "REBALANCE",
                    "CASH",
                    "EQUAL_WEIGHT",
                )
                put(
                    SESSION,
                    "HOUR",
                    "WEEKDAY",
                    "UTC",
                    "NY",
                    "LONDON",
                    "TOKYO",
                    "SYDNEY",
                    "CHICAGO",
                    "BROKER",
                    "WARMUP",
                    "BARS",
                )
                put(HOOK, "ON_FILL", "ON_STOP", "ON_TP", "ON_CLOSE")
                put(
                    STATE,
                    "POSITION",
                    "POSITION_AVG_PRICE",
                    "OPEN_ORDERS",
                    "ACCOUNT",
                    "STREAK",
                    "TRADES",
                    "COOLDOWN",
                    "NOW",
                    "EXIT",
                    "SYMBOL",
                    "ENTRY_QTY",
                )
                put(OPERATOR_WORD, "AND", "OR", "NOT", "IS", "NULL", "BETWEEN", "CROSSES", "ABOVE", "BELOW")
                put(AGGREGATE, "OPEN", "MAX", "MIN", "MEAN", "SUM")
                put(LITERAL, "TRUE", "FALSE")
            }

        /** The category of the keyword spelled [spelling] (uppercase), or null when it is not a keyword. */
        fun of(spelling: String): KeywordCategory? = table[spelling.uppercase()]

        /** The category of [kind], or null for literal, identifier and punctuation kinds (including `ARROW`). */
        fun of(kind: TokenKind): KeywordCategory? = table[kind.name]

        /** Every categorized keyword spelling, in [KeywordCategory] then declaration order. */
        fun spellings(category: KeywordCategory): List<String> = table.filterValues { it == category }.keys.toList()

        /** Every categorized keyword spelling mapped to its category. */
        fun all(): Map<String, KeywordCategory> = table
    }
}
