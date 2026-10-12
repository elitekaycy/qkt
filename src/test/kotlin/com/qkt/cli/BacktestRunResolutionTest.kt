package com.qkt.cli

import com.qkt.evidence.ResolvedValue
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestRunResolutionTest {
    @Test
    fun `cli param beats the configured one with flag provenance`(
        @TempDir tmp: Path,
    ) {
        val strat = Files.writeString(tmp.resolve("s.qkt"), "STRATEGY s VERSION 1\n")
        val cfg =
            Config(
                backtest =
                    BacktestSection(
                        defaults = mapOf("from" to "2025-09-01"),
                        defaultParams = listOf("fast=3"),
                    ),
            )
        val r =
            BacktestRunResolution.resolve(
                Args(arrayOf("backtest", strat.toString(), "--param", "fast=9")),
                cfg,
            )
        assertThat(r.paramOverrides).containsEntry("fast", "9")
        assertThat(r.resolved["param.fast"]).isEqualTo(ResolvedValue("9", "flag"))
        assertThat(r.resolved["from"]).isEqualTo(ResolvedValue("2025-09-01", "global"))
        assertThat(r.resolved["strategy"]).isEqualTo(ResolvedValue(strat.toString(), "file"))
    }

    @Test
    fun `boolean flags never absorb the following token as their value`(
        @TempDir tmp: Path,
    ) {
        val strat = Files.writeString(tmp.resolve("s.qkt"), "STRATEGY s VERSION 1\n")
        val r =
            BacktestRunResolution.resolve(
                Args(arrayOf("backtest", strat.toString(), "--json", "--report-dir", "out", "--from", "2025-09-01")),
                Config(),
            )
        assertThat(r.resolved["json"]).isEqualTo(ResolvedValue("true", "flag"))
        assertThat(r.resolved["report-dir"]).isEqualTo(ResolvedValue("out", "flag"))
        assertThat(r.resolved["from"]).isEqualTo(ResolvedValue("2025-09-01", "flag"))
    }

    @Test
    fun `configured strategy resolves against the config dir`(
        @TempDir tmp: Path,
    ) {
        val cfg =
            Config(
                backtest = BacktestSection(strategy = "strat/named.qkt"),
                configDir = tmp,
            )
        val r = BacktestRunResolution.resolve(Args(arrayOf("backtest")), cfg)
        assertThat(r.strategy).isEqualTo(tmp.resolve("strat/named.qkt"))
        assertThat(r.resolved["strategy"]).isEqualTo(ResolvedValue("strat/named.qkt", "global"))
    }
}
