package com.qkt.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResearchCommandTest {
    @Test
    fun `research loads ticks with standard instrument specs and exits on EOF`(
        @TempDir dir: Path,
    ) {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
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
        val code =
            ResearchCommand(
                Args(
                    arrayOf(
                        "research",
                        strat.toString(),
                        "--from",
                        "2026-06-04",
                        "--to",
                        "2026-06-05",
                        "--data-root",
                        dir.resolve("data").toString(),
                    ),
                ),
                fetcherOverride = FakeXauFetcher,
                input = ByteArrayInputStream(ByteArray(0)),
                output = PrintStream(out),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("loaded")
    }
}
