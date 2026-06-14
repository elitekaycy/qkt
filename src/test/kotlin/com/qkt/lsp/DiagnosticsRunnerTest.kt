package com.qkt.lsp

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test

class DiagnosticsRunnerTest {
    @Test
    fun `valid strategy parses with no diagnostics`() {
        val src =
            """
            STRATEGY example VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > 100
                THEN BUY btc SIZING 1
            """.trimIndent()

        val analysis = DiagnosticsRunner.analyze(src)

        assertThat(analysis.diagnostics).isEmpty()
        assertThat(analysis.parsed).isNotNull()
    }

    @Test
    fun `syntax error produces a positioned error diagnostic sourced to qkt`() {
        val src =
            """
            STRATEGY s VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULE
                WHEN btc.close > 100
                THEN BUY btc SIZING 1
            """.trimIndent()

        val analysis = DiagnosticsRunner.analyze(src)

        assertThat(analysis.parsed).isNull()
        assertThat(analysis.diagnostics).isNotEmpty()
        val first = analysis.diagnostics.first()
        assertThat(first.severity).isEqualTo(DiagnosticSeverity.Error)
        assertThat(first.source).isEqualTo("qkt")
        // `RULE` (typo for `RULES`) is on the sixth line, reported 0-based as line 5.
        assertThat(first.range.start.line).isEqualTo(5)
    }

    @Test
    fun `error range spans the whole offending token`() {
        val src =
            """
            STRATEGY s VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULE
                WHEN btc.close > 100
                THEN BUY btc SIZING 1
            """.trimIndent()

        val diag =
            DiagnosticsRunner
                .analyze(src)
                .diagnostics
                .first { it.range.start.line == 5 }

        // The offending token `RULE` is four characters wide; the range must cover it,
        // not collapse to a single caret.
        assertThat(diag.range.start.character).isEqualTo(0)
        assertThat(diag.range.end.character).isEqualTo(4)
    }

    @Test
    fun `unterminated string yields a diagnostic instead of crashing`() {
        val src =
            """
            STRATEGY s VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > 100
                THEN BUY btc SIZING 1 TAG 'oops
            """.trimIndent()

        val analysis = DiagnosticsRunner.analyze(src)

        assertThat(analysis.parsed).isNull()
        assertThat(analysis.diagnostics).hasSize(1)
        val only = analysis.diagnostics.single()
        assertThat(only.severity).isEqualTo(DiagnosticSeverity.Error)
        assertThat(only.source).isEqualTo("qkt")
        // The unterminated string starts on the eighth line, reported 0-based as line 7.
        assertThat(only.range.start.line).isEqualTo(7)
    }
}
