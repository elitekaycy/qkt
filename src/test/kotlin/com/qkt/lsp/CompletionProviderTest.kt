package com.qkt.lsp

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.junit.jupiter.api.Test

class CompletionProviderTest {
    private val doc =
        """
        STRATEGY s VERSION 1

        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m

        LET fast = 9

        RULES
            WHEN btc.close > fast
            THEN BUY btc SIZING 1
        """.trimIndent()

    private fun astOf(src: String) = DiagnosticsRunner.analyze(src).parsed

    @Test
    fun `expression position offers indicators and functions in lower case`() {
        val labels = CompletionProvider.complete("WHEN ", 0, 5, null).map { it.label }
        assertThat(labels).contains("ema", "rsi", "abs")
    }

    @Test
    fun `expression position offers section and operator keywords in upper case`() {
        val labels = CompletionProvider.complete("WHEN ", 0, 5, null).map { it.label }
        assertThat(labels).contains("STRATEGY", "RULES", "CROSSES")
    }

    @Test
    fun `completion includes stream aliases and lets from the parsed ast`() {
        val labels = CompletionProvider.complete("WHEN ", 0, 5, astOf(doc)).map { it.label }
        assertThat(labels).contains("btc", "fast")
    }

    @Test
    fun `member access after a known alias offers candle fields only`() {
        // Cursor sits right after `btc.` on the ninth line (0-based line 8, char 13),
        // driving real multi-line offset math through the full document text.
        val labels = CompletionProvider.complete(doc, 8, 13, astOf(doc)).map { it.label }
        assertThat(labels).contains("close", "open", "high", "low", "volume")
        assertThat(labels).doesNotContain("ema", "rsi", "STRATEGY")
    }

    @Test
    fun `member access after an unknown owner offers nothing`() {
        val labels = CompletionProvider.complete("x = unknown.", 0, 12, astOf(doc)).map { it.label }
        assertThat(labels).isEmpty()
    }

    @Test
    fun `an empty document offers the whole-file templates as expandable snippets`() {
        val items = CompletionProvider.complete("", 0, 0, null)
        val strategy = items.first { it.label == "strategy" && it.kind == CompletionItemKind.Snippet }
        assertThat(strategy.insertTextFormat).isEqualTo(InsertTextFormat.Snippet)
        assertThat(strategy.insertText).contains("STRATEGY", "DEFAULTS", "SYMBOLS", "RULES")
        assertThat(items.map { it.label }).contains("stratfull", "strat-ema").doesNotContain("rule", "buy")
    }

    @Test
    fun `member access does not offer snippets`() {
        val kinds = CompletionProvider.complete(doc, 8, 13, astOf(doc)).map { it.kind }
        assertThat(kinds).doesNotContain(CompletionItemKind.Snippet)
    }

    @Test
    fun `snippets are offered only where they produce valid DSL`() {
        val doc = "STRATEGY s VERSION 1\n\nSYMBOLS\n  btc = BACKTEST:BTCUSD EVERY 15m\n  \n\n\nRULES\n  \n"

        fun snippetsAt(
            line: Int,
            ch: Int,
        ) = CompletionProvider
            .complete(
                doc,
                line,
                ch,
                null,
            ).filter { it.kind == CompletionItemKind.Snippet }
            .map { it.label }
        assertThat(snippetsAt(4, 2)).containsExactly("sym")
        assertThat(snippetsAt(6, 0)).contains("let", "def").doesNotContain("rule", "strategy")
        assertThat(
            snippetsAt(8, 2),
        ).contains("rule", "foreach", "flatten").doesNotContain("let", "def", "strategy", "sym")
        assertThat(
            CompletionProvider.complete("PORTFOLIO p VERSION 1\n", 1, 0, null).filter {
                it.kind ==
                    CompletionItemKind.Snippet
            },
        ).isEmpty()
    }

    private val richDoc =
        """
        STRATEGY s VERSION 1

        SYMBOLS
            aud = BACKTEST:AUDUSD EVERY 1h
            nzd = BACKTEST:NZDUSD EVERY 1h
            anti = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
            eq = SERIES ACCOUNT.EQUITY EVERY 1h

        SEQUENCE setup ON aud {
            STAGE dip: aud.close < 1
            STAGE pop: aud.close > 1
        }

        RULES
            WHEN aud.close > 0
            THEN BUY aud SIZING 1
        """.trimIndent()

    private fun after(prefix: String) = CompletionProvider.complete(prefix, 0, prefix.length, astOf(richDoc))

    @Test
    fun `POSITION dot alias offers the position accessors as fields, not candle fields`() {
        val items = after("WHEN POSITION.aud.")
        assertThat(items.map { it.label }).contains("pnl", "entry_price", "holding_duration").doesNotContain("close")
        assertThat(items.map { it.kind }).containsOnly(CompletionItemKind.Field)
    }

    @Test
    fun `POSITION dot offers stream and basket aliases as variables`() {
        val items = after("WHEN POSITION.")
        assertThat(items.map { it.label }).containsExactly("aud", "nzd", "anti")
        assertThat(items.map { it.kind }).containsOnly(CompletionItemKind.Variable)
        assertThat(after("WHEN POSITION.unknown.")).isEmpty()
    }

    @Test
    fun `NOW and ACCOUNT dot offer their members without an ast`() {
        assertThat(
            CompletionProvider.complete("WHEN NOW.", 0, 9, null).map { it.label },
        ).contains("hour_utc", "epoch_ms")
        assertThat(
            CompletionProvider.complete("WHEN ACCOUNT.", 0, 13, null).map { it.label },
        ).contains("equity", "dd_pct")
    }

    @Test
    fun `SEQUENCE chains resolve the sequence, its stages and the stage members`() {
        assertThat(after("WHEN SEQUENCE.").map { it.label }).containsExactly("setup")
        assertThat(after("WHEN SEQUENCE.setup.").map { it.label }).containsExactly("stage", "complete", "dip", "pop")
        assertThat(after("WHEN SEQUENCE.setup.dip.").map { it.label }).containsExactly("price", "time")
        assertThat(after("WHEN SEQUENCE.other.")).isEmpty()
    }

    @Test
    fun `stream basket and series aliases offer their fields plus the candle and tick selectors`() {
        assertThat(after("WHEN aud.").map { it.label }).contains("close", "tick_size", "candle", "tick")
        assertThat(after("WHEN anti.").map { it.label }).contains("close", "candle", "tick").doesNotContain("tick_size")
        assertThat(after("WHEN eq.").map { it.label }).contains("close", "candle").doesNotContain("tick_size")
    }

    @Test
    fun `general completion includes basket, series and sequence names`() {
        assertThat(after("WHEN ").map { it.label }).contains("aud", "anti", "eq", "setup")
    }
}
