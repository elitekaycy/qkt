package com.qkt.cli

import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepCommandEdgesTest : SweepCommandFixture() {
    @TempDir
    lateinit var dir: Path

    private fun sweep(
        strategy: Path,
        vararg extra: String,
        fetcher: DataFetcher = FakeXauFetcher,
    ) = captureCliRun {
        SweepCommand(
            Args(
                arrayOf(
                    "sweep",
                    strategy.toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "fast=2,3",
                    *extra,
                ),
            ),
            fetcherOverride = fetcher,
        ).run()
    }

    @Test
    fun `a strategy that does not parse is reported line by line`() {
        val broken = dir.resolve("broken.qkt")
        Files.writeString(broken, "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")

        val run = sweep(broken)

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).containsPattern("broken\\.qkt:\\d+:\\d+ — ")
    }

    @Test
    fun `a setup error is reported before any trial runs`() {
        val run = sweep(strategy(dir), "--position-mode", "fifo")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("unknown position mode 'fifo'")
    }

    @Test
    fun `days the fetcher cannot supply refuse the sweep`() {
        val nothing =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ) = Unit
            }

        val run = sweep(strategy(dir), fetcher = nothing)

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: error: ").contains("XAUUSD")
    }

    @Test
    fun `a malformed scenarios file is a user error`() {
        val scenarios = dir.resolve("scenarios.yaml")
        Files.writeString(scenarios, "label: not-a-list\n")

        val run = sweep(strategy(dir), "--scenarios", scenarios.toString())

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("scenarios file: top-level must be a list")
    }

    @Test
    fun `a bars sweep runs each combo as its own backtest`() {
        val data = dir.resolve("data")
        FakeXauFetcher.fetch("XAUUSD", LocalDate.parse("2026-06-04"), data.resolve("symbols/XAUUSD/2026-06-04.csv.gz"))
        val build = arrayOf("data", "build-bars", "XAUUSD", "--tf", "1m", "--data-root", data.toString())
        assertThat(captureCliRun { DataCommand(Args(build)).run() }.code).isEqualTo(ExitCodes.SUCCESS)

        val run = sweep(strategy(dir), "--bars", "--json")

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.err).contains("qkt: sweeping 2 scenario(s)")
        assertThat(run.out).contains("fast=2").contains("fast=3")
    }
}
