package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreflightCommandProductionControlsTest : PreflightCommandFixture() {
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
              disk_free_alert_gb: 0
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
        assertThat(text).contains("PASS state.disk_headroom")
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
              bybit_linear:
                type: gateway
                gateway_url: http://127.0.0.1:1
                api_key: token
                expected_adapter: bybit
                expected_account_login: "1"
                expected_trade_mode: demo
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
              bybit_linear:
                type: gateway
                gateway_url: http://127.0.0.1:1
                api_key: token
                expected_adapter: bybit
                expected_account_login: "1"
                expected_trade_mode: demo
            notify:
              telegram:
                enabled: true
                bot_token:
                chat_id:
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
    fun `an unknown runtime mode fails as a one-line check, not a stack trace`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            runtime:
              mode: research
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
        assertThat(text).contains("FAIL config.load: runtime.mode must be one of")
        assertThat(text).doesNotContain("at com.qkt")
    }
}
