package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigTest {
    @Test
    fun `loads top-level fields with env-var expansion`() {
        System.setProperty("TEST_BYBIT_API_KEY", "secret_value")
        val c = Config.load(Path.of("src/test/resources/cli/qkt.config.yaml"))
        assertThat(c.source).isEqualTo("bybit")
        assertThat(c.dataRoot).isEqualTo("./fixtures")
        assertThat(c.startingBalance).isEqualByComparingTo("10000")
        assertThat(c.brokers["bybit"]?.get("api_key")).isEqualTo("secret_value")
    }

    @Test
    fun `missing config returns defaults`() {
        val c = Config.load(Path.of("nonexistent.yaml"))
        assertThat(c.source).isEqualTo("local")
        assertThat(c.dataRoot).isEqualTo("./data")
        assertThat(c.startingBalance).isEqualByComparingTo("0")
    }

    @Test
    fun `default-syntax expands to the default when var is unset`(
        @TempDir tmp: Path,
    ) {
        // Pick a name that should not exist in the env / system properties.
        check(System.getenv("QKT_UNSET_TEST_VAR") == null)
        check(System.getProperty("QKT_UNSET_TEST_VAR") == null)
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            source: tv
            brokers:
              exness:
                type: mt5
                gateway_url: ${'$'}{QKT_UNSET_TEST_VAR:-http://mt5-gateway:5001}
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.brokers["exness"]?.get("gateway_url"))
            .isEqualTo("http://mt5-gateway:5001")
    }

    @Test
    fun `env value wins over the default`(
        @TempDir tmp: Path,
    ) {
        System.setProperty("QKT_TEST_GATEWAY_URL", "http://override.local:9999")
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            brokers:
              exness:
                type: mt5
                gateway_url: ${'$'}{QKT_TEST_GATEWAY_URL:-http://fallback:5001}
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.brokers["exness"]?.get("gateway_url"))
            .isEqualTo("http://override.local:9999")
    }

    @Test
    fun `risk max_daily_loss reads from config`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            risk:
              max_daily_loss: 250
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.maxDailyLoss).isEqualByComparingTo("250")
    }

    @Test
    fun `runtime mode and waivers parse from config`(
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
                  reason: "pager maintenance window"
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.runtimeMode).isEqualTo(RuntimeMode.PRODUCTION)
        assertThat(c.runtimeWaiver("alerts")).isEqualTo("pager maintenance window")
    }

    @Test
    fun `accounting and fx conversion config parse`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            account:
              currency: USD
            fx_conversion:
              source: local
              missing_policy: fail
              symbols:
                USDJPY: BACKTEST:USDJPY
                CHFUSD: BACKTEST:CHFUSD
            """.trimIndent(),
        )
        val c = Config.load(cfg)

        assertThat(c.accountCurrency).isEqualTo("USD")
        assertThat(c.accountingConfig.missingPolicy).isEqualTo(com.qkt.accounting.FxMissingPolicy.FAIL)
        assertThat(c.accountingConfig.source).isEqualTo("local")
        assertThat(c.accountingConfig.normalizedSymbols)
            .containsEntry("USDJPY", "BACKTEST:USDJPY")
            .containsEntry("CHFUSD", "BACKTEST:CHFUSD")
    }

    @Test
    fun `execution simulation config parses`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            execution:
              preset: mt5-realistic
              seed: 42
              latency: fixed:250ms
              slippage: fixed-points:7
            """.trimIndent(),
        )

        val c = Config.load(cfg)

        assertThat(c.execution).containsEntry("preset", "mt5-realistic")
        assertThat(c.execution).containsEntry("seed", "42")
        assertThat(c.execution).containsEntry("latency", "fixed:250ms")
        assertThat(c.execution).containsEntry("slippage", "fixed-points:7")
    }

    @Test
    fun `promotion gate config parses and production enforces by default`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            runtime:
              mode: production
            promotion:
              required_state: small-capital
              dataset_snapshot: true
              realistic_execution: true
              walk_forward: true
              paper_days: 14
              paper_min_trades: 75
              max_paper_slippage_bps: 4.5
              registry_dir: ${tmp.resolve("promotions")}
            """.trimIndent(),
        )

        val c = Config.load(cfg)
        val gates = c.promotionGateConfig

        assertThat(gates.enforce).isTrue()
        assertThat(gates.requiredState).isEqualTo(PromotionState.SMALL_CAPITAL)
        assertThat(gates.requireDatasetSnapshot).isTrue()
        assertThat(gates.requireRealisticExecution).isTrue()
        assertThat(gates.requireWalkForward).isTrue()
        assertThat(gates.minPaperDays).isEqualTo(14)
        assertThat(gates.minPaperTrades).isEqualTo(75)
        assertThat(gates.maxPaperSlippageBps).isEqualTo(4.5)
        assertThat(gates.registryDir).isEqualTo(tmp.resolve("promotions"))
    }

    @Test
    fun `risk max_daily_loss defaults when absent`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: tv\n")
        val c = Config.load(cfg)
        assertThat(c.maxDailyLoss).isEqualByComparingTo(Config.DEFAULT_MAX_DAILY_LOSS)
    }

    @Test
    fun `risk max_daily_loss of zero disables the rule`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            risk:
              max_daily_loss: 0
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.maxDailyLoss).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `var without default leaves literal when unset`(
        @TempDir tmp: Path,
    ) {
        check(System.getenv("QKT_NEVER_SET_VAR") == null)
        check(System.getProperty("QKT_NEVER_SET_VAR") == null)
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            brokers:
              x:
                api_key: ${'$'}{QKT_NEVER_SET_VAR}
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        // Literal flows through — caller is on the hook to fail loudly when reading.
        assertThat(c.brokers["x"]?.get("api_key")).isEqualTo("\${QKT_NEVER_SET_VAR}")
    }
}
