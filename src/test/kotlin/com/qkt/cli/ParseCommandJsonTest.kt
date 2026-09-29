package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt parse --json` describes what a file trades and the calendar qkt applies (#1278). */
class ParseCommandJsonTest {
    private fun capture(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(err))
                runMain(arrayOf(*argv))
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
            }
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `describes every stream with its venue, symbol, timeframe and calendar`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY dual VERSION 3
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1h
                btc = BYBIT:BTCUSDT EVERY 15m
            RULES
                WHEN gold.close > 0 AND btc.close > 0
                THEN BUY gold SIZING 0.1
            """.trimIndent(),
        )

        val (code, out, err) = capture("parse", path.toString(), "--json")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(err).isEmpty()
        assertThat(out.trim())
            .startsWith("{\"schema\":\"qkt-parse-v1\",\"kind\":\"strategy\",\"name\":\"dual\",\"version\":3,")
            .contains(
                "{\"alias\":\"gold\",\"venue\":\"BACKTEST\",\"symbol\":\"XAUUSD\",\"qktSymbol\":\"BACKTEST:XAUUSD\",\"timeframe\":\"1h\",\"warmupBars\":null,\"calendar\":\"fx\"}",
            ).contains(
                "{\"alias\":\"btc\",\"venue\":\"BYBIT\",\"symbol\":\"BTCUSDT\",\"qktSymbol\":\"BYBIT:BTCUSDT\",\"timeframe\":\"15m\",\"warmupBars\":null,\"calendar\":\"crypto\"}",
            ).doesNotContain("ok")
    }

    @Test
    fun `an invalid file still exits non-zero with the same diagnostics`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("broken.qkt")
        Files.writeString(path, "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")

        val (code, out, err) = capture("parse", path.toString(), "--json")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).isEmpty()
        assertThat(err).contains("broken.qkt:").contains("error")
    }
}
