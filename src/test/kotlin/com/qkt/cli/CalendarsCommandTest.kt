package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CalendarsCommandTest {
    @TempDir
    lateinit var tmp: Path

    private fun calendars(vararg argv: String) =
        captureCliRun { CalendarsCommand(Args(arrayOf("calendars", *argv))).run() }

    private fun strategy(
        name: String = "dual",
        body: String =
            """
            STRATEGY dual VERSION 1
            SYMBOLS
                eur = BACKTEST:EURUSD EVERY 1h
                btc = BACKTEST:BTCUSDT EVERY 1h
            RULES
                WHEN eur.close > 0 AND btc.close > 0 THEN BUY eur SIZING 0.1
            """.trimIndent(),
    ): Path = tmp.resolve("$name.qkt").also { Files.writeString(it, body) }

    private fun noConfig() = tmp.resolve("absent.yaml").toString()

    @Test
    fun `prints each symbol's calendar and source and the run and book samples a year`() {
        val run = calendars(strategy().toString(), "--config", noConfig())

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        val lines = run.out.lines()
        assertThat(lines[0]).matches("symbol\\s+calendar\\s+source")
        assertThat(lines[1]).matches("BACKTEST:EURUSD\\s+fx\\s+built-in default")
        assertThat(lines[2]).matches("BACKTEST:BTCUSDT\\s+crypto\\s+built-in '\\*USDT'")
        assertThat(run.out).contains("samples a year at 1h:")
        val runSamples = BigDecimal(Regex("run sharpe .*?\\s([0-9.]+)\\n").find(run.out)!!.groupValues[1])
        val bookSamples = BigDecimal(Regex("book risk .*?\\s([0-9.]+)\\n").find(run.out)!!.groupValues[1])
        assertThat(bookSamples).isGreaterThan(runSamples)
        assertThat(bookSamples.toDouble()).isBetween(8700.0, 8800.0)
    }

    @Test
    fun `json prints the calendars schema with one entry per distinct symbol`() {
        val run = calendars(strategy().toString(), "--config", noConfig(), "--json")

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim())
            .startsWith(
                "{\"schema\":\"qkt-calendars-v1\",\"symbols\":[" +
                    "{\"symbol\":\"BACKTEST:EURUSD\",\"calendar\":\"fx\",\"source\":\"built-in default\"}," +
                    "{\"symbol\":\"BACKTEST:BTCUSDT\",\"calendar\":\"crypto\",\"source\":\"built-in '*USDT'\"}]," +
                    "\"timeframe\":\"1h\",\"runSamplesPerYear\":",
            ).contains("\"bookSamplesPerYear\":")
    }

    @Test
    fun `a portfolio lists every child's streams`() {
        strategy(
            "child",
            """
            STRATEGY child VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 15m
            RULES
                WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent(),
        )
        val book =
            strategy(
                "book",
                """
                PORTFOLIO book VERSION 1
                IMPORT 'child.qkt' AS child
                RULES
                  RUN child
                """.trimIndent(),
            )

        val run = calendars(book.toString(), "--config", noConfig())

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("BACKTEST:XAUUSD").contains("samples a year at 15m:")
    }

    @Test
    fun `a missing or unparsable file is a user error`() {
        val missing = calendars(tmp.resolve("nope.qkt").toString())
        assertThat(missing.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(missing.err).contains("file not found:")

        val broken = strategy("broken", "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")
        val bad = calendars(broken.toString())
        assertThat(bad.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(bad.err).startsWith("qkt: error: $broken: ")
    }

    @Test
    fun `unreadable broker calendars in the config are refused naming the reason`() {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            brokers:
              exness:
                type: mt5
                gateway_url: http://localhost:1
                calendars:
                  "EURUSD": no-such-calendar
            """.trimIndent(),
        )
        val file =
            strategy(
                "ex",
                """
                STRATEGY ex VERSION 1
                SYMBOLS
                    eur = EXNESS:EURUSD EVERY 1h
                RULES
                    WHEN eur.close > 0 THEN BUY eur SIZING 0.1
                """.trimIndent(),
            )

        val run = calendars(file.toString(), "--config", config.toString())

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("broker calendars in the config cannot be read")
    }
}
