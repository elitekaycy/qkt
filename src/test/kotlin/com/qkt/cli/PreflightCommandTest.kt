package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        assertThat(text).contains("PASS strategy.parse: book portfolio v1 (1 child strategies)")
        assertThat(text).contains("PASS symbol.metadata")
        assertThat(text).doesNotContain("FAIL")
    }

    @Test
    fun `production preflight fails when enabled telegram lacks credentials`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            runtime:
              mode: production
            risk:
              max_daily_loss: 100
            brokers:
              bybit:
                type: bybit
            notify:
              telegram:
                enabled: true
                bot_token:
                chat_id:
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
        assertThat(out.toString()).contains("FAIL notify.alerts: enabled alert channel is missing required credentials")
    }

    @Test
    fun `production preflight verifies MT5 account and symbol visibility`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"balance":10000,"equity":10000,"currency":"USD","leverage":100,"margin_mode":2,"login":435898347,"server":"Exness-MT5Trial9","trade_mode":0}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"ask":1.1,"bid":1.0999,"digits":5,"point":0.00001,"volume_min":0.01,"volume_step":0.01,"trade_contract_size":100000}""",
                ),
            )
            val strategy = tmp.resolve("mt5.qkt")
            Files.writeString(
                strategy,
                """
                STRATEGY mt5 VERSION 1
                SYMBOLS
                    eur = EXNESS:EURUSD EVERY 1m
                RULES
                    WHEN eur.close > 0 THEN BUY eur SIZING 0.1
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
                account:
                  currency: USD
                risk:
                  max_daily_loss: 100
                brokers:
                  exness:
                    type: mt5
                    gateway_url: ${server.url("/").toString().trimEnd('/')}
                    retry_attempts: 0
                    expected_account_login: 435898347
                    expected_account_server: Exness-MT5Trial9
                    expected_trade_mode: demo
                    expected_leverage: 100
                """.trimIndent(),
            )

            val checks =
                ProductionPreflight.evaluate(
                    configPath = cfg,
                    stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                    strategyPath = strategy,
                )

            assertThat(checks.single { it.name == "broker.gateway.exness" }.status)
                .isEqualTo(PreflightStatus.PASS)
            assertThat(server.takeRequest().path).isEqualTo("/account")
            assertThat(server.takeRequest().path).isEqualTo("/symbol_info/EURUSDm")
        } finally {
            server.shutdown()
        }
    }
}
