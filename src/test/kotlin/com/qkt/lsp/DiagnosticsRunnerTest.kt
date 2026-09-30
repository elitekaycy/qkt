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

    @Test
    fun `a compile error is a diagnostic underlining the identifier the compiler names`() {
        val src =
            """
            STRATEGY s VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN bogus(btc.candle, 14) > 100
                THEN BUY btc SIZING 1
            """.trimIndent()

        val analysis = DiagnosticsRunner.analyze(src)

        assertThat(analysis.parsed).isNotNull()
        val only = analysis.diagnostics.single()
        assertThat(only.message).isEqualTo("Unknown indicator: bogus")
        assertThat(only.severity).isEqualTo(DiagnosticSeverity.Error)
        assertThat(only.source).isEqualTo("qkt")
        // `bogus` sits on the seventh line after four spaces and `WHEN `: 0-based line 6, char 9..14.
        assertThat(only.range.start.line).isEqualTo(6)
        assertThat(only.range.start.character).isEqualTo(9)
        assertThat(only.range.end.character).isEqualTo(14)
    }

    @Test
    fun `an undeclared alias is reported at compile time on the alias`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN xyz.close > 0 THEN BUY btc SIZING 0.1
            """.trimIndent()

        val only = DiagnosticsRunner.analyze(src).diagnostics.single()

        assertThat(only.message).isEqualTo("Unknown stream alias: xyz")
        assertThat(only.range.start.line).isEqualTo(4)
        assertThat(only.range.start.character).isEqualTo(9)
        assertThat(only.range.end.character).isEqualTo(12)
    }

    @Test
    fun `parse and compile diagnostics share one shape`() {
        val header = "STRATEGY s VERSION 1\nSYMBOLS\n    btc = BACKTEST:BTCUSDT EVERY 1m\n"
        val parseFailure = header + "RULE\n    WHEN 1 > 0 THEN BUY btc SIZING 1"
        val compileFailure = header + "RULES\n    WHEN nope(btc.candle, 2) > 0 THEN BUY btc SIZING 1"

        val fromParse = DiagnosticsRunner.analyze(parseFailure).diagnostics.first()
        val fromCompile = DiagnosticsRunner.analyze(compileFailure).diagnostics.single()

        for (d in listOf(fromParse, fromCompile)) {
            assertThat(d.severity).isEqualTo(DiagnosticSeverity.Error)
            assertThat(d.source).isEqualTo("qkt")
            assertThat(d.range.start.line).isGreaterThanOrEqualTo(0)
            assertThat(d.range.start.character).isGreaterThanOrEqualTo(0)
            assertThat(d.range.end.character).isGreaterThan(d.range.start.character)
        }
    }

    @Test
    fun `a portfolio validation error never produces a negative range`() {
        // No IMPORT: the portfolio AST rejects itself and the parser reports it at line 0.
        val src =
            """
            PORTFOLIO book VERSION 1
            RULES
            """.trimIndent()

        val analysis = DiagnosticsRunner.analyze(src)

        assertThat(analysis.diagnostics).isNotEmpty()
        for (d in analysis.diagnostics) {
            assertThat(d.range.start.line).isGreaterThanOrEqualTo(0)
            assertThat(d.range.start.character).isGreaterThanOrEqualTo(0)
            assertThat(d.range.end.line).isGreaterThanOrEqualTo(0)
            assertThat(d.range.end.character).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `a portfolio whose own rules conflict is diagnosed without reading its children`() {
        val src =
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
                WHEN 1 > 0 RUN child OVERRIDE { n = 1 }
                WHEN 2 > 0 RUN child OVERRIDE { n = 2 }
            """.trimIndent()

        val analysis = DiagnosticsRunner.analyze(src)

        assertThat(analysis.parsed).isNotNull()
        assertThat(analysis.diagnostics.single().message).contains("conflicting OVERRIDE for alias 'child'")
        assertThat(
            analysis.diagnostics
                .single()
                .range.start.line,
        ).isGreaterThanOrEqualTo(0)
    }
}
