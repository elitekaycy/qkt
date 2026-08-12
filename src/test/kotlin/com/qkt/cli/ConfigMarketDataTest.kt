package com.qkt.cli

import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketDataGateConfig
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigMarketDataTest {
    @Test
    fun `absent market_data block keeps the gate's built-in thresholds`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(cfg, "source: local\n")
        val c = Config.load(cfg)
        assertThat(c.marketData).isEqualTo(MarketDataGateConfig.DEFAULT)
        assertThat(c.marketData.staleAgeMultiple).isEqualTo(MarketDataGate.DEFAULT_STALE_AGE_MULTIPLE)
        assertThat(c.marketData.minStaleAgeMs).isEqualTo(MarketDataGate.DEFAULT_MIN_STALE_AGE_MS)
        assertThat(c.marketData.outlierSigma).isEqualTo(MarketDataGate.DEFAULT_OUTLIER_SIGMA)
        assertThat(c.marketData.maxClockSkewMs).isEqualTo(MarketDataGate.DEFAULT_MAX_CLOCK_SKEW_MS)
    }

    @Test
    fun `market_data block parses all four gate thresholds`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            market_data:
              stale_age_multiple: 8.0
              min_stale_age_ms: 30000
              outlier_sigma: 4.5
              max_clock_skew_ms: 120000
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.marketData.staleAgeMultiple).isEqualTo(8.0)
        assertThat(c.marketData.minStaleAgeMs).isEqualTo(30_000L)
        assertThat(c.marketData.outlierSigma).isEqualTo(4.5)
        assertThat(c.marketData.maxClockSkewMs).isEqualTo(120_000L)
    }

    @Test
    fun `market_data block with a single key defaults the rest`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            market_data:
              min_stale_age_ms: 20000
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.marketData.minStaleAgeMs).isEqualTo(20_000L)
        assertThat(c.marketData.staleAgeMultiple).isEqualTo(MarketDataGate.DEFAULT_STALE_AGE_MULTIPLE)
        assertThat(c.marketData.outlierSigma).isEqualTo(MarketDataGate.DEFAULT_OUTLIER_SIGMA)
        assertThat(c.marketData.maxClockSkewMs).isEqualTo(MarketDataGate.DEFAULT_MAX_CLOCK_SKEW_MS)
    }
}
