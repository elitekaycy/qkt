package com.qkt.cli

import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WalkForwardCommandEdgesTest : SweepCommandFixture() {
    @TempDir
    lateinit var dir: Path

    private fun walkforward(
        strategy: Path,
        vararg extra: String,
        train: String = "6h",
        fetcher: DataFetcher = FakeXauFetcher,
    ) = captureCliRun {
        WalkForwardCommand(
            Args(
                arrayOf(
                    "walkforward",
                    strategy.toString(),
                    "--from",
                    "2026-06-04T00:00:00",
                    "--to",
                    "2026-06-04T12:00:00",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "fast=2,3",
                    "--train",
                    train,
                    "--test",
                    "3h",
                    "--step",
                    "3h",
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

        val run = walkforward(broken)

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).containsPattern("broken\\.qkt:\\d+:\\d+ — ")
    }

    @Test
    fun `an unknown rank and a malformed duration are user errors`() {
        val rank = walkforward(strategy(dir), "--rank", "bogus")
        assertThat(rank.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(rank.err).startsWith("qkt: error: ")

        val train = walkforward(strategy(dir), train = "a while")
        assertThat(train.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(train.err.trim()).isEqualTo("qkt: error: --train must be a duration like 90d, 12h, 30m")
    }

    @Test
    fun `a setup error and missing days refuse the run`() {
        val setup = walkforward(strategy(dir), "--position-mode", "fifo")
        assertThat(setup.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(setup.err).contains("unknown position mode 'fifo'")

        val nothing =
            object : DataFetcher {
                override fun fetch(
                    symbol: String,
                    day: LocalDate,
                    target: Path,
                ) = Unit
            }
        val missing = walkforward(strategy(dir), fetcher = nothing)
        assertThat(missing.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(missing.err).contains("XAUUSD")
    }

    @Test
    fun `a training window longer than the range is refused by the harness`() {
        val run = walkforward(strategy(dir), train = "30d")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: error: totalRange duration").contains("too short for trainSize + testSize")
    }
}
