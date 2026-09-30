package com.qkt.lsp

/**
 * The strategy templates a `.qkt` author can drop into a file: a full strategy skeleton,
 * a ready-to-edit example, and smaller building blocks (a rule, a BUY, a crossover).
 *
 * This object is the single source for those templates. The language server serves them as
 * snippet completions (so any LSP editor expands them inline — type `strategy`, press enter,
 * fill the tab stops), and the VS Code extension's `snippets/qkt.json` is generated from the
 * exact same list via [toVscodeJson], so the two can never drift.
 *
 * Bodies are written in LSP/TextMate snippet syntax: `$1` is a tab stop, `${1:name}` a tab
 * stop with default text, `${1|a,b|}` a choice, repeated `$1` mirror the same edit, and `$0`
 * is where the cursor lands last. e.g. typing `rule` inserts `WHEN <cursor> ... THEN ...`.
 */
object QktSnippets {
    /**
     * One reusable template. [prefix] is what the author types to trigger it; [body] is the
     * inserted text (one entry per line) in snippet syntax; [title] is the human label and
     * [description] the one-line explanation shown in the completion popup.
     */
    data class Snippet(
        val title: String,
        val prefix: String,
        val body: List<String>,
        val description: String,
        val scope: Scope,
    )

    /**
     * Where a snippet produces valid DSL, so the language server offers it only there: a whole-file
     * template in an empty document, a stream line inside SYMBOLS, a declaration before RULES, and
     * rules or rule fragments inside RULES. e.g. `let` is never offered inside RULES, which rejects it.
     */
    enum class Scope { FILE, SYMBOLS, DECLARATION, RULES }

    val all: List<Snippet> =
        listOf(
            Snippet(
                title = "STRATEGY skeleton",
                prefix = "strategy",
                body =
                    listOf(
                        "STRATEGY \${1:name} VERSION \${2:1}",
                        "",
                        "DEFAULTS {",
                        "  SIZING = \${3:0.1}",
                        "  TIF = \${4|GTC,IOC,FOK,DAY|}",
                        "}",
                        "",
                        "SYMBOLS",
                        "  \${5:btc} = " +
                            "\${6|BACKTEST,BYBIT_SPOT,BYBIT_LINEAR,EXNESS,ICMARKETS|}" +
                            ":\${7:BTCUSDT} EVERY \${8|1m,5m,15m,1h,1d|}",
                        "",
                        "RULES",
                        "  WHEN \${9:\$5.close > \$5.open}",
                        "  THEN \${10:BUY \$5}",
                        "\$0",
                    ),
                description = "STRATEGY skeleton with DEFAULTS, SYMBOLS, and one RULES entry.",
                scope = Scope.FILE,
            ),
            Snippet(
                title = "STRATEGY skeleton (full)",
                prefix = "stratfull",
                body =
                    listOf(
                        "STRATEGY \${1:name} VERSION \${2:1}",
                        "",
                        "DEFAULTS {",
                        "  SIZING = \${3:0.1}",
                        "  TIF = \${4|GTC,IOC,FOK,DAY|}",
                        "}",
                        "",
                        "SYMBOLS",
                        "  \${5:btc} = " +
                            "\${6|BACKTEST,BYBIT_SPOT,BYBIT_LINEAR,EXNESS,ICMARKETS|}" +
                            ":\${7:BTCUSDT} EVERY \${8|1h,15m,5m,1m,1d|}",
                        "",
                        "PARAM \${9:fast} = \${10:9}",
                        "PARAM \${11:slow} = \${12:21}",
                        "",
                        "LET \${13:fast_ema} = ema(\$5.close, \$9)",
                        "LET \${14:slow_ema} = ema(\$5.close, \$11)",
                        "",
                        "RULES",
                        "  WHEN \$13 CROSSES ABOVE \$14",
                        "  THEN BUY \$5",
                        "",
                        "  WHEN \$13 CROSSES BELOW \$14",
                        "  THEN CLOSE \$5",
                        "\$0",
                    ),
                description = "Full STRATEGY skeleton: DEFAULTS, SYMBOLS, PARAMs driving a crossover entry/exit.",
                scope = Scope.FILE,
            ),
            Snippet(
                title = "EMA crossover strategy (complete)",
                prefix = "strat-ema",
                body =
                    listOf(
                        "STRATEGY ema_cross VERSION 1",
                        "",
                        "DEFAULTS {",
                        "  SIZING = \${1:0.1}",
                        "}",
                        "",
                        "SYMBOLS",
                        "  \${2:btc} = " +
                            "\${3|BACKTEST,BYBIT_SPOT,BYBIT_LINEAR,EXNESS,ICMARKETS|}" +
                            ":\${4:BTCUSDT} EVERY \${5|1h,15m,5m,1m,1d|}",
                        "",
                        "RULES",
                        "  WHEN ema(\$2.close, \${6:9}) CROSSES ABOVE ema(\$2.close, \${7:21})",
                        "  THEN BUY \$2",
                        "",
                        "  WHEN ema(\$2.close, \${6:9}) CROSSES BELOW ema(\$2.close, \${7:21})",
                        "  THEN CLOSE \$2",
                        "\$0",
                    ),
                description = "A complete, runnable EMA fast/slow crossover strategy to edit.",
                scope = Scope.FILE,
            ),
            Snippet(
                title = "SYMBOLS line",
                prefix = "sym",
                body =
                    listOf(
                        "\${1:alias} = " +
                            "\${2|BACKTEST,BYBIT_SPOT,BYBIT_LINEAR,EXNESS,ICMARKETS|}" +
                            ":\${3:BTCUSDT} EVERY \${4|1m,5m,15m,1h,1d|}" +
                            "\${5: WARMUP \${6:50} BARS}",
                    ),
                description = "Stream declaration with optional WARMUP.",
                scope = Scope.SYMBOLS,
            ),
            Snippet(
                title = "WHEN/THEN rule",
                prefix = "rule",
                body =
                    listOf(
                        "WHEN \${1:alias}.close > \$1.open",
                        "THEN BUY \$1 SIZING \${2:0.1}",
                    ),
                description = "Basic WHEN/THEN rule.",
                scope = Scope.RULES,
            ),
            Snippet(
                title = "BUY action",
                prefix = "buy",
                body =
                    listOf(
                        "BUY \${1:alias} SIZING \${2:0.1}",
                    ),
                description = "BUY action with explicit sizing.",
                scope = Scope.RULES,
            ),
            Snippet(
                title = "BUY with bracket",
                prefix = "buybr",
                body =
                    listOf(
                        "BUY \${1:alias} SIZING \${2:0.1}",
                        "    BRACKET {",
                        "      STOP LOSS BY \${3:atr(\${1:alias}, 14) * 2},",
                        "      TAKE PROFIT BY \${4:atr(\${1:alias}, 14) * 4}",
                        "    }",
                    ),
                description = "BUY with ATR-sized stop and take-profit.",
                scope = Scope.RULES,
            ),
            Snippet(
                title = "SIZING N PCT RISK",
                prefix = "pctrisk",
                body =
                    listOf(
                        "SIZING \${1:0.5} PCT RISK",
                    ),
                description = "Risk-percent sizing.",
                scope = Scope.RULES,
            ),
            Snippet(
                title = "EMA crossover",
                prefix = "cross",
                body =
                    listOf(
                        "ema(\${1:alias}.close, \${2:9}) CROSSES ABOVE ema(\${1:alias}.close, \${3:21})",
                    ),
                description = "EMA fast/slow crossover condition.",
                scope = Scope.RULES,
            ),
            Snippet(
                title = "LET binding",
                prefix = "let",
                body =
                    listOf(
                        "LET \${1:trend} = \${2:ema(alias.close, 20)}",
                    ),
                description = "LET expression binding (before RULES).",
                scope = Scope.DECLARATION,
            ),
            Snippet(
                title = "DEFAULTS block",
                prefix = "def",
                body =
                    listOf(
                        "DEFAULTS {",
                        "  SIZING = \${1:0.1}",
                        "  TIF = \${2|GTC,IOC,FOK,DAY|}",
                        "}",
                    ),
                description = "DEFAULTS block (before SYMBOLS).",
                scope = Scope.DECLARATION,
            ),
            Snippet(
                title = "FOR EACH over streams",
                prefix = "foreach",
                body =
                    listOf(
                        "FOR EACH \${1:s} IN [\${2:btc, eth, sol}] DO",
                        "  WHEN \$1.close > \$1.open",
                        "  THEN BUY \$1 SIZING \${3:0.1}",
                    ),
                description = "Iterate a rule over multiple streams (the list needs its brackets).",
                scope = Scope.RULES,
            ),
            Snippet(
                title = "Session-end FLATTEN",
                prefix = "flatten",
                body =
                    listOf(
                        "WHEN NOW.hour_utc = \${1:21} THEN FLATTEN",
                    ),
                description = "Close every open position at a fixed UTC hour.",
                scope = Scope.RULES,
            ),
            Snippet(
                title = "IS NOT NULL guard",
                prefix = "notnull",
                body =
                    listOf(
                        "\${1:expression} IS NOT NULL",
                    ),
                description = "Guard expression against Value.Undefined.",
                scope = Scope.RULES,
            ),
        )
}
