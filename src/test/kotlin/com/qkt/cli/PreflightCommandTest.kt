package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreflightCommandTest {
    private fun strategy(tmp: Path): Path {
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
        return path
    }

    @Test
    fun `production preflight fails closed when mandatory controls are absent`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            runtime:
              mode: production
            state:
              enabled: false
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(out))
            val code =
                PreflightCommand(
                    Args(
                        arrayOf(
                            "preflight",
                            strategy(tmp).toString(),
                            "--config",
                            cfg.toString(),
                            "--state-dir",
                            tmp.resolve("state").toString(),
                        ),
                    ),
                ).run()
            assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        } finally {
            System.setOut(original)
        }
        val text = out.toString()
        assertThat(text).contains("FAIL state.persistence")
        assertThat(text).contains("FAIL risk.config")
        assertThat(text).contains("FAIL broker.config")
        assertThat(text).contains("FAIL notify.alerts")
        assertThat(text).contains("PASS journal.append_only")
    }

    @Test
    fun `production preflight passes with explicit risk broker state journal and alert waiver`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            runtime:
              mode: production
              waivers:
                alerts:
                  reason: "integration test"
            risk:
              max_daily_loss: 100
            brokers:
              bybit:
                type: bybit
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(out))
            val code =
                PreflightCommand(
                    Args(
                        arrayOf(
                            "preflight",
                            strategy(tmp).toString(),
                            "--config",
                            cfg.toString(),
                            "--state-dir",
                            tmp.resolve("state").toString(),
                        ),
                    ),
                ).run()
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(original)
        }
        val text = out.toString()
        assertThat(text).contains("PASS runtime.mode: production")
        assertThat(text).contains("PASS risk.config")
        assertThat(text).contains("PASS broker.config")
        assertThat(text).contains("WARN notify.alerts: waived: integration test")
        assertThat(text).doesNotContain("FAIL")
    }
}
