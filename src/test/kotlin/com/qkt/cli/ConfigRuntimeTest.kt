package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigRuntimeTest {
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
}
