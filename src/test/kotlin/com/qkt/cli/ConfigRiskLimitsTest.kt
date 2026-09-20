package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigRiskLimitsTest {
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
    fun `live equity basis parses modeled and rejects unknown values`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "risk:\n  live_equity_basis: modeled\n")
        assertThat(Config.load(cfg).liveEquityBasis).isEqualTo(com.qkt.app.LiveEquityBasis.MODELED)

        Files.writeString(cfg, "risk:\n  live_equity_basis: mixed\n")
        val error = runCatching { Config.load(cfg).liveEquityBasis }.exceptionOrNull()
        assertThat(error).hasMessageContaining("valid: venue, modeled")
    }

    @Test
    fun `runaway breaker thresholds read from risk config`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            risk:
              max_round_trips_10m: 24
              max_broker_rejections_1m: 8
            """.trimIndent(),
        )

        val c = Config.load(cfg)

        assertThat(c.runawayMaxRoundTrips).isEqualTo(24)
        assertThat(c.runawayMaxRejections).isEqualTo(8)
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
}
