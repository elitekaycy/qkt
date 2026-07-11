package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ParseCommandTest {
    private fun runParse(file: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = ParseCommand(Args(arrayOf("parse", file))).run()
            code to (out.toString() + err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `valid strategy exits 0`() {
        val (code, out) = runParse("src/test/resources/cli/valid_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("ok")
    }

    @Test
    fun `valid portfolio exits 0 and validates imports`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(
            tmp.resolve("child.qkt"),
            """
            STRATEGY child VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0 THEN BUY gold SIZING 0.1
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

        val (code, out) = runParse(portfolio.toString())

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("ok")
    }

    @Test
    fun `portfolio with missing child exits 1`(
        @TempDir tmp: Path,
    ) {
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO book VERSION 1
            IMPORT 'missing.qkt' AS child
            RULES
                RUN child
            """.trimIndent(),
        )

        val (code, out) = runParse(portfolio.toString())

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("error")
    }

    @Test
    fun `broken strategy exits 1 with error list`() {
        val (code, out) = runParse("src/test/resources/cli/broken_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("broken_strategy.qkt:")
        assertThat(out).contains("error")
    }

    @Test
    fun `missing file exits 1`() {
        val (code, _) = runParse("does_not_exist.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
