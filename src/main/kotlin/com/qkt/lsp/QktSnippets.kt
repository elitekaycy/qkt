package com.qkt.lsp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
    )

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
                        "  \${5:alias} = " +
                            "\${6|BACKTEST,BYBIT_SPOT,BYBIT_LINEAR,EXNESS,ICMARKETS|}" +
                            ":\${7:SYMBOL} EVERY \${8|1m,5m,15m,1h,1d|}",
                        "",
                        "RULES",
                        "  WHEN \${9:condition}",
                        "  THEN \${10:action}",
                        "\$0",
                    ),
                description = "STRATEGY skeleton with DEFAULTS, SYMBOLS, and one RULES entry.",
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
                        "LET \${13:fast_ema} = ema(\$5.close, \${10:9})",
                        "",
                        "RULES",
                        "  WHEN ema(\$5.close, \${10:9}) CROSSES ABOVE ema(\$5.close, \${12:21})",
                        "  THEN BUY \$5",
                        "",
                        "  WHEN ema(\$5.close, \${10:9}) CROSSES BELOW ema(\$5.close, \${12:21})",
                        "  THEN CLOSE \$5",
                        "\$0",
                    ),
                description = "Full STRATEGY skeleton: DEFAULTS, SYMBOLS, PARAM, LET, and a crossover entry/exit.",
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
            ),
            Snippet(
                title = "SYMBOLS line",
                prefix = "sym",
                body =
                    listOf(
                        "\${1:alias} = " +
                            "\${2|BACKTEST,BYBIT_SPOT,BYBIT_LINEAR,EXNESS,ICMARKETS|}" +
                            ":\${3:SYMBOL} EVERY \${4|1m,5m,15m,1h,1d|}" +
                            "\${5: WARMUP \${6:50} BARS}",
                    ),
                description = "Stream declaration with optional WARMUP.",
            ),
            Snippet(
                title = "WHEN/THEN rule",
                prefix = "rule",
                body =
                    listOf(
                        "WHEN \${1:condition}",
                        "THEN \${2:action}",
                    ),
                description = "Basic WHEN/THEN rule.",
            ),
            Snippet(
                title = "BUY action",
                prefix = "buy",
                body =
                    listOf(
                        "BUY \${1:alias} SIZING \${2:0.1}",
                    ),
                description = "BUY action with explicit sizing.",
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
            ),
            Snippet(
                title = "SIZING N PCT RISK",
                prefix = "pctrisk",
                body =
                    listOf(
                        "SIZING \${1:0.5} PCT RISK",
                    ),
                description = "Risk-percent sizing.",
            ),
            Snippet(
                title = "EMA crossover",
                prefix = "cross",
                body =
                    listOf(
                        "ema(\${1:alias}.close, \${2:9}) CROSSES ABOVE ema(\${1:alias}.close, \${3:21})",
                    ),
                description = "EMA fast/slow crossover condition.",
            ),
            Snippet(
                title = "LET binding",
                prefix = "let",
                body =
                    listOf(
                        "LET \${1:name} = \${2:expression}",
                    ),
                description = "LET expression binding.",
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
                description = "DEFAULTS block.",
            ),
            Snippet(
                title = "FOR EACH over streams",
                prefix = "foreach",
                body =
                    listOf(
                        "FOR EACH \${1:s} IN \${2:btc, eth, sol} DO",
                        "  WHEN \${3:condition involving \${1:s}}",
                        "  THEN \${4:action involving \${1:s}}",
                    ),
                description = "Iterate a rule over multiple streams.",
            ),
            Snippet(
                title = "Session-end FLATTEN",
                prefix = "flatten",
                body =
                    listOf(
                        "WHEN NOW.hour_utc = \${1:21} THEN FLATTEN",
                    ),
                description = "Close every open position at a fixed UTC hour.",
            ),
            Snippet(
                title = "IS NOT NULL guard",
                prefix = "notnull",
                body =
                    listOf(
                        "\${1:expression} IS NOT NULL",
                    ),
                description = "Guard expression against Value.Undefined.",
            ),
        )

    /**
     * Render [all] as the VS Code snippets file (`editor/vscode/snippets/qkt.json`): a JSON
     * object keyed by [Snippet.title], each value carrying `prefix`, `body`, and `description`.
     * VS Code snippet syntax is identical to the LSP's, so the bodies are emitted verbatim.
     */
    fun toVscodeJson(): String {
        val map = LinkedHashMap<String, VscodeSnippet>()
        for (s in all) map[s.title] = VscodeSnippet(s.prefix, s.body, s.description)
        return JSON.encodeToString(MapSerializer(String.serializer(), VscodeSnippet.serializer()), map)
    }

    @Serializable
    private data class VscodeSnippet(
        val prefix: String,
        val body: List<String>,
        val description: String,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val JSON =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
}
