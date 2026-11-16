package com.qkt.cli

import com.qkt.evidence.ResolvedValue
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
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
        assertThat(r.resolved["strategy"]).isEqualTo(ResolvedValue(Path.of("strat/named.qkt").toString(), "global"))
    }

    private fun named(
        section: BacktestSection,
        vararg argv: String,
    ) = BacktestRunResolution.resolve(Args(arrayOf("backtest", *argv)), Config(backtest = section))

    @Test
    fun `a named run uses its own strategy and params, layered over the global ones`() {
        val section =
            BacktestSection(
                defaults = mapOf("from" to "2025-09-01"),
                defaultParams = listOf("fast=3", "slow=20"),
                strategy = "s/global.qkt",
                runs = mapOf("scalp" to BacktestRun(mapOf("from" to "2025-10-01"), listOf("fast=8"), "s/scalp.qkt")),
            )

        val r = named(section, "scalp.qkt")

        assertThat(r.runName).isEqualTo("scalp")
        assertThat(r.strategy).isEqualTo(Path.of("s/scalp.qkt"))
        assertThat(r.paramOverrides).containsExactly(entry("fast", "8"), entry("slow", "20"))
        assertThat(r.effective.option("from")).isEqualTo("2025-10-01")
        assertThat(r.resolved).containsAllEntriesOf(
            mapOf(
                "strategy" to ResolvedValue("s/scalp.qkt", "run:scalp"),
                "from" to ResolvedValue("2025-10-01", "run:scalp"),
                "param.fast" to ResolvedValue("8", "run:scalp"),
                "param.slow" to ResolvedValue("20", "global"),
            ),
        )
    }

    @Test
    fun `a named run without a strategy inherits the global one, and fails when there is none`() {
        val runs = mapOf("wide" to BacktestRun(), "tight" to BacktestRun())

        val r = named(BacktestSection(strategy = "s/global.qkt", runs = runs), "wide")
        assertThat(r.strategy).isEqualTo(Path.of("s/global.qkt"))
        assertThat(r.resolved["strategy"]).isEqualTo(ResolvedValue("s/global.qkt", "global"))

        assertThatThrownBy { named(BacktestSection(runs = runs), "wide") }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("no strategy for run 'wide' and backtest.strategy is unset\n  runs: tight, wide")
    }

    @Test
    fun `an unknown run and a bare backtest with runs but no strategy are argument errors listing the runs`() {
        val section = BacktestSection(runs = mapOf("scalp" to BacktestRun(strategy = "s.qkt")))

        assertThatThrownBy { named(section, "scalps") }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("no file 'scalps' and no run 'scalps'. Did you mean 'scalp'?\n  runs: scalp")
        assertThatThrownBy { named(section) }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("no strategy given: pass a file, name a run, or set backtest.strategy\n  runs: scalp")
        assertThatThrownBy { named(BacktestSection()) }
            .isInstanceOf(ArgError::class.java)
            .hasMessageStartingWith("missing required argument: <strategy.qkt>")
    }

    @Test
    fun `a comma in a param value or a param without a name is a setup error pointing at sweep`() {
        val setupError = BacktestContext.Companion.SetupError::class.java
        val configured = BacktestSection(strategy = "s.qkt", defaultParams = listOf("fast=3,5"))

        assertThatThrownBy { named(configured) }
            .isInstanceOf(setupError)
            .hasMessage("multiple values for 'fast' in config param; use 'qkt sweep' to grid-search")
        assertThatThrownBy { named(BacktestSection(strategy = "s.qkt"), "--param", "fast=3,5") }
            .isInstanceOf(setupError)
            .hasMessage("multiple values for 'fast'; use 'qkt sweep' to grid-search")
        for (bad in listOf("fast", "=3")) {
            assertThatThrownBy { named(BacktestSection(strategy = "s.qkt"), "--param", bad) }
                .isInstanceOf(setupError)
                .hasMessage("bad --param '$bad'; expected NAME=VALUE")
        }
    }
}
