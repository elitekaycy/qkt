package com.qkt.cli

import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExperimentCommandSeamsTest {
    @TempDir
    lateinit var dir: Path

    private val strategySource =
        """
        STRATEGY s VERSION 1
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 1m
        LET fast = 3
        RULES
            WHEN ema(gold.close, fast) CROSSES ABOVE ema(gold.close, 9)
            THEN BUY gold SIZING 0.1
            WHEN ema(gold.close, fast) CROSSES BELOW ema(gold.close, 9)
            THEN CLOSE gold
        """.trimIndent()

    private val userDirs by lazy { UserDirs(osName = "linux", env = emptyMap(), home = dir.resolve("home")) }

    private fun plan(grid: String = "fast: [2, 3]"): Path {
        val strategy = dir.resolve("s.qkt").also { Files.writeString(it, strategySource) }
        return dir.resolve("plan.yaml").also {
            Files.writeString(
                it,
                "name: xau research\nstrategy: $strategy\nprimary_metric: totalPnL\nsplits:\n" +
                    "  train: 2026-06-04T00:00:00Z/2026-06-04T04:00:00Z\n" +
                    "  validation: 2026-06-04T04:00:00Z/2026-06-04T08:00:00Z\n" +
                    "  test: 2026-06-04T08:00:00Z/2026-06-04T12:00:00Z\n" +
                    "parameter_grid:\n  $grid\nselection:\n  top_n: 1\n",
            )
        }
    }

    private fun experiment(
        vararg argv: String,
        fetcher: DataFetcher? = FakeXauFetcher,
    ) = captureCliRun {
        ExperimentCommand(
            Args(arrayOf("experiment", *argv, "--data-root", dir.resolve("data").toString())),
            fetcherOverride = fetcher,
            userDirs = userDirs,
        ).run()
    }

    @Test
    fun `a backtest setup the context refuses is a user error naming it`() {
        val run = experiment("run", "--plan", plan().toString(), "--broker", "ibkr")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: error: unknown --broker 'ibkr'")
    }

    @Test
    fun `a grid naming a parameter the strategy lacks is a compile error from the sweep`() {
        FakeXauFetcher.fetch(
            "XAUUSD",
            LocalDate.parse("2026-06-04"),
            dir.resolve("data/symbols/XAUUSD/2026-06-04.csv.gz"),
        )

        assertThatThrownBy { experiment("run", "--plan", plan("nope: [1, 2]").toString()) }
            .isInstanceOf(com.qkt.dsl.compile.CompileError::class.java)
            .hasMessageContaining("unknown parameter 'nope'; strategy declares: [fast]")
    }

    @Test
    fun `without a registry or output directory a run lands under the state home`() {
        FakeXauFetcher.fetch(
            "XAUUSD",
            LocalDate.parse("2026-06-04"),
            dir.resolve("data/symbols/XAUUSD/2026-06-04.csv.gz"),
        )

        val run = experiment("run", "--plan", plan().toString())

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        val experiments = userDirs.stateHome().resolve("experiments")
        assertThat(experiments.resolve("runs/xau_research")).isDirectory()
        assertThat(run.out).contains("record: $experiments")
    }
}
