package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt parse` prints compile errors at the position the editor underlines, not at `1:1`. */
class ParseCommandPositionsTest {
    private fun runParse(path: Path): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val code =
            try {
                System.setOut(PrintStream(ByteArrayOutputStream()))
                System.setErr(PrintStream(err))
                ParseCommand(Args(arrayOf("parse", path.toString()))).run()
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        return code to err.toString()
    }

    @Test
    fun `parse prints the line and column of a compile error`(
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
                THEN BUY gold SIZING 1
                WHEN bogus(gold.candle, 14) > 0
                THEN BUY gold SIZING 1
            """.trimIndent(),
        )

        val (code, err) = runParse(path)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err.lines()).containsExactly("$path:7:10 — Unknown indicator: bogus", "1 error", "")
    }

    @Test
    fun `parse reports a child's compile error on the portfolio's IMPORT line`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(
            tmp.resolve("child.qkt"),
            """
            STRATEGY child VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN bogus(gold.candle, 14) > 0 THEN BUY gold SIZING 1
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

        val (code, err) = runParse(portfolio)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).startsWith("$portfolio:3:23 — child 'child' (")
        assertThat(err).contains("Unknown indicator: bogus")
    }
}
