package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreflightCommandStrategyValidationTest : PreflightCommandFixture() {
    @Test
    fun `production preflight validates portfolio roots and children`(
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
            state:
              disk_free_alert_gb: 0
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
                            portfolio.toString(),
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
        assertThat(text).contains("PASS strategy.parse: book portfolio v1")
        assertThat(text).contains("PASS strategy.compile: book portfolio v1 (1 child strategies)")
        assertThat(text).contains("PASS symbol.metadata")
        assertThat(text).doesNotContain("FAIL")
    }

    @Test
    fun `preflight fails when a parsed strategy cannot compile`(
        @TempDir tmp: Path,
    ) {
        val strategy = tmp.resolve("invalid-risk.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY invalid_risk VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0
                THEN BUY gold SIZING 0.5 PCT RISK
            """.trimIndent(),
        )
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(config, "runtime:\n  mode: dev\n")

        val checks =
            ProductionPreflight.evaluate(
                configPath = config,
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                strategyPath = strategy,
            )

        assertThat(checks).anySatisfy { check ->
            assertThat(check.name).isEqualTo("strategy.compile")
            assertThat(check.status).isEqualTo(PreflightStatus.FAIL)
            assertThat(check.detail).contains("resolvable stop distance")
        }
    }

    @Test
    fun `crypto symbol on the fx weekend calendar is flagged with the rule to add`(
        @TempDir tmp: Path,
    ) {
        val strategy = tmp.resolve("btc.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY btc VERSION 1
            SYMBOLS
                c = EXNESS:BTCUSD EVERY 1m
            RULES
                WHEN c.close > 0 THEN FLATTEN
            """.trimIndent(),
        )
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            runtime:
              mode: dev
            brokers:
              exness:
                type: mt5
                gateway_url: http://127.0.0.1:5001
            """.trimIndent(),
        )

        val flagged =
            ProductionPreflight.evaluate(
                configPath = config,
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                strategyPath = strategy,
                offline = true,
            )
        assertThat(flagged).anySatisfy { check ->
            assertThat(check.name).isEqualTo("symbol.calendar")
            assertThat(check.status).isEqualTo(PreflightStatus.WARN)
            assertThat(check.detail).contains("EXNESS:BTCUSD").contains("\"BTC*\": crypto")
        }

        Files.writeString(
            config,
            """
            runtime:
              mode: dev
            brokers:
              exness:
                type: mt5
                gateway_url: http://127.0.0.1:5001
                calendars:
                  "BTC*": crypto
                  "XAU*":
                    base: fx
                    pause: 17:00-18:00
                    zone: America/New_York
            """.trimIndent(),
        )
        val fixed =
            ProductionPreflight.evaluate(
                configPath = config,
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                strategyPath = strategy,
                offline = true,
            )
        assertThat(fixed).anySatisfy { check ->
            assertThat(check.name).isEqualTo("symbol.calendar")
            assertThat(check.status).isEqualTo(PreflightStatus.PASS)
        }
    }
}
