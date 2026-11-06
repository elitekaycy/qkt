package com.qkt.cli

import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExperimentCommandEdgesTest {
    @TempDir
    lateinit var dir: Path

    private val validStrategy =
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

    private fun plan(
        strategyLine: String,
        datasetLine: String = "",
    ): Path =
        dir.resolve("plan.yaml").also {
            Files.writeString(
                it,
                "name: xau research\n$strategyLine\n$datasetLine\nprimary_metric: totalPnL\nsplits:\n" +
                    "  train: 2026-06-04T00:00:00Z/2026-06-04T04:00:00Z\n" +
                    "  validation: 2026-06-04T04:00:00Z/2026-06-04T08:00:00Z\n" +
                    "  test: 2026-06-04T08:00:00Z/2026-06-04T12:00:00Z\n" +
                    "parameter_grid:\n  fast: [2, 3]\nselection:\n  top_n: 1\n",
            )
        }

    private fun strategy(body: String = validStrategy): Path = dir.resolve("s.qkt").also { Files.writeString(it, body) }

    private fun experiment(
        vararg argv: String,
        fetcher: DataFetcher? = null,
    ) = captureCliRun {
        ExperimentCommand(
            Args(arrayOf("experiment", *argv, "--data-root", dir.resolve("data").toString())),
            fetcherOverride = fetcher,
        ).run()
    }

    @Test
    fun `only the run action is accepted`() {
        val run = experiment("start")

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run.err).contains("usage: qkt experiment run --plan <research-plan.yaml>")
    }

    @Test
    fun `an unreadable plan a plan without a strategy and a missing strategy file are user errors`() {
        val unreadable = experiment("run", "--plan", dir.resolve("absent.yaml").toString())
        assertThat(unreadable.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unreadable.err).startsWith("qkt: error: ")

        val noStrategy = experiment("run", "--plan", plan("").toString())
        assertThat(noStrategy.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noStrategy.err).contains("strategy must be set in the plan or with --strategy")

        val gone = experiment("run", "--plan", plan("").toString(), "--strategy", dir.resolve("gone.qkt").toString())
        assertThat(gone.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(gone.err).contains("strategy file not found:")
    }

    @Test
    fun `a strategy that does not parse is reported with its position`() {
        val broken = strategy("STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")

        val run = experiment("run", "--plan", plan("strategy: $broken").toString())

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).containsPattern("s\\.qkt:\\d+:\\d+ — ")
    }

    @Test
    fun `days the fetcher cannot supply refuse the experiment`() {
        val nothing =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ) = Unit
            }

        val run = experiment("run", "--plan", plan("strategy: ${strategy()}").toString(), fetcher = nothing)

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).startsWith("qkt: error: ").contains("XAUUSD")
    }

    @Test
    fun `json prints the persisted record and runs land under the registry by default`() {
        val dataRoot = dir.resolve("data")
        FakeXauFetcher.fetch(
            "XAUUSD",
            LocalDate.parse("2026-06-04"),
            dataRoot.resolve("symbols/XAUUSD/2026-06-04.csv.gz"),
        )
        val registry = dir.resolve("registry")

        val run =
            experiment(
                "run",
                "--plan",
                plan("strategy: ${strategy()}").toString(),
                "--registry-dir",
                registry.toString(),
                "--json",
                fetcher = FakeXauFetcher,
            )

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim()).startsWith("{").contains("\"name\":\"xau research\"")
        val runs = registry.resolve("runs/xau_research")
        assertThat(Files.list(runs).use { it.count() }).isEqualTo(1L)
    }
}
