package com.qkt.lsp

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.Hover
import org.junit.jupiter.api.Test

class HoverProviderTest {
    private val doc =
        """
        STRATEGY s VERSION 1

        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m

        RULES
            WHEN ema(btc.close, 9) > 100
            THEN BUY btc SIZING 1
        """.trimIndent()

    private fun astOf(src: String) = DiagnosticsRunner.analyze(src).parsed

    private fun textOf(hover: Hover?) = hover?.contents?.right?.value

    @Test
    fun `hover on an indicator shows its signature and description`() {
        // Cursor on `ema` (char 6) in `WHEN ema(...`.
        val hover = HoverProvider.hover("WHEN ema(btc.close, 9)", 0, 6, null)
        assertThat(textOf(hover)).contains("ema(value, period)").contains("moving average")
    }

    @Test
    fun `hover on a function shows its signature`() {
        // Cursor on `abs` (char 9) in `LET x = abs(...`.
        val hover = HoverProvider.hover("LET x = abs(btc.close)", 0, 9, null)
        assertThat(textOf(hover)).contains("abs(x)")
    }

    @Test
    fun `hover on a constant shows its value`() {
        // Cursor inside `ONE_PERCENT` (char 16).
        val hover = HoverProvider.hover("SIZING RISK $ ONE_PERCENT", 0, 16, null)
        assertThat(textOf(hover)).contains("ONE_PERCENT").contains("0.01")
    }

    @Test
    fun `hover on a keyword shows one-line help`() {
        // Cursor on `WHEN` (char 1).
        val hover = HoverProvider.hover("WHEN x > 1", 0, 1, null)
        assertThat(textOf(hover)).contains("Condition")
    }

    @Test
    fun `hover on a stream alias shows the stream declaration`() {
        // Cursor on `btc` (line 6, char 13) inside `WHEN ema(btc.close, 9)`.
        val hover = HoverProvider.hover(doc, 6, 13, astOf(doc))
        assertThat(textOf(hover)).contains("BTCUSDT")
    }

    @Test
    fun `hover off any word returns null`() {
        // Cursor on the middle of three spaces — not adjacent to any identifier.
        val hover = HoverProvider.hover("WHEN   x", 0, 5, null)
        assertThat(hover).isNull()
    }
}
