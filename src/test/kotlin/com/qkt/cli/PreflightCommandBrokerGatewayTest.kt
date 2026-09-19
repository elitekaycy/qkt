package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreflightCommandBrokerGatewayTest : PreflightCommandFixture() {
    @Test
    fun `offline preflight skips broker gateway checks without connecting`(
        @TempDir tmp: Path,
    ) {
        val strategy = tmp.resolve("s.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY s VERSION 1
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
                gateway_url: http://unreachable-gateway:9999
                retry_attempts: 0
            """.trimIndent(),
        )

        val onlineChecks =
            ProductionPreflight.evaluate(
                configPath = cfg,
                stateDir = StateDir.resolve(tmp.resolve("state-online").toString()),
                strategyPath = strategy,
            )
        assertThat(onlineChecks.single { it.name == "broker.gateway.exness" }.status)
            .isEqualTo(PreflightStatus.FAIL)

        val offlineChecks =
            ProductionPreflight.evaluate(
                configPath = cfg,
                stateDir = StateDir.resolve(tmp.resolve("state-offline").toString()),
                strategyPath = strategy,
                offline = true,
            )
        assertThat(offlineChecks.single { it.name == "broker.gateway.exness" }).satisfies(
            { check ->
                assertThat(check.status).isEqualTo(PreflightStatus.WARN)
                assertThat(check.detail).contains("offline")
            },
        )
        assertThat(offlineChecks.none { it.status == PreflightStatus.FAIL }).isTrue()
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
                    expected_margin_mode: hedging
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
