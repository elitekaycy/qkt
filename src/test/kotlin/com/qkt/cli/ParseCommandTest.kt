package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ParseCommandTest {
    @Test
    fun `parse succeeds for a valid strategy file`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0
                THEN BUY gold SIZING 0.1
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            val code = ParseCommand(Args(arrayOf("parse", path.toString()))).run()
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        assertThat(out.toString()).contains("ok")
        assertThat(err.toString()).isEmpty()
    }

    @Test
    fun `parse fails for a strategy with an unresolvable risk sizing action`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY s VERSION 1
            DEFAULTS {
                SIZING = 1 PCT RISK
            }
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0
                THEN BUY gold
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            val code = ParseCommand(Args(arrayOf("parse", path.toString()))).run()
            assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        val errText = err.toString()
        assertThat(errText).contains("SIZING RISK")
        assertThat(errText).contains("STOP LOSS")
    }

    @Test
    fun `parse succeeds for a pct risk strategy with a bracket stop loss`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY s VERSION 1
            DEFAULTS {
                SIZING = 1 PCT RISK
            }
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            PARAM slpct = 0.5
            RULES
                WHEN gold.close > 0
                THEN BUY gold
                    BRACKET {
                        STOP LOSS BY slpct PCT,
                        TAKE PROFIT BY 1 PCT
                    }
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            val code = ParseCommand(Args(arrayOf("parse", path.toString()))).run()
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        assertThat(out.toString()).contains("ok")
        assertThat(err.toString()).isEmpty()
    }

    @Test
    fun `parse fails for a portfolio child with an unresolvable risk sizing action`(
        @TempDir tmp: Path,
    ) {
        val child = tmp.resolve("child.qkt")
        Files.writeString(
            child,
            """
            STRATEGY child VERSION 1
            DEFAULTS {
                SIZING = 1 PCT RISK
            }
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0
                THEN BUY gold
            """.trimIndent(),
        )
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
                RUN child
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            val code = ParseCommand(Args(arrayOf("parse", portfolio.toString()))).run()
            assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        val errText = err.toString()
        assertThat(errText).contains("SIZING RISK")
        assertThat(errText).contains("STOP LOSS")
    }

    @Test
    fun `parse succeeds for a portfolio child with a param bracket stop loss`(
        @TempDir tmp: Path,
    ) {
        val child = tmp.resolve("child.qkt")
        Files.writeString(
            child,
            """
            STRATEGY child VERSION 1
            DEFAULTS {
                SIZING = 1 PCT RISK
            }
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            PARAM slpct = 0.5
            RULES
                WHEN gold.close > 0
                THEN BUY gold
                    BRACKET {
                        STOP LOSS BY slpct PCT,
                        TAKE PROFIT BY 1 PCT
                    }
            """.trimIndent(),
        )
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
                RUN child
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            val code = ParseCommand(Args(arrayOf("parse", portfolio.toString()))).run()
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        assertThat(out.toString()).contains("ok")
        assertThat(err.toString()).isEmpty()
    }
}
