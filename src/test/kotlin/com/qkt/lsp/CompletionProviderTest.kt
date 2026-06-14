package com.qkt.lsp

import org.assertj.core.api.Assertions.assertThat
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
}
