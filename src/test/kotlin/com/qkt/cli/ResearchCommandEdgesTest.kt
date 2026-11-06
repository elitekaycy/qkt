package com.qkt.cli

import com.qkt.marketdata.store.DataFetcher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResearchCommandEdgesTest : SweepCommandFixture() {
    @TempDir
    lateinit var dir: Path

    private val nothing =
        object : DataFetcher {
            override fun fetch(
                symbol: String,
                day: LocalDate,
                target: Path,
            ) = Unit
        }

    private fun research(
        strategy: Path,
        vararg extra: String,
        fetcher: DataFetcher = FakeXauFetcher,
    ): Pair<CapturedCliRun, String> {
        val repl = ByteArrayOutputStream()
        val run =
            captureCliRun {
                ResearchCommand(
                    Args(
                        arrayOf(
                            "research",
                            strategy.toString(),
                            "--from",
                            "2026-06-04",
                            "--to",
                            "2026-06-05",
                            "--data-root",
                            dir.resolve("data").toString(),
                            *extra,
                        ),
                    ),
                    fetcherOverride = fetcher,
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = PrintStream(repl, true),
                ).run()
            }
        return run to repl.toString()
    }

    @Test
    fun `a strategy that does not parse is reported line by line`() {
        val broken = dir.resolve("broken.qkt")
        Files.writeString(broken, "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")

        val (run, _) = research(broken)

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).containsPattern("broken\\.qkt:\\d+:\\d+ — ")
    }

    @Test
    fun `an unknown broker or fetcher is a setup error`() {
        val (broker, _) = research(strategy(dir), "--broker", "ib")
        assertThat(broker.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(broker.err).contains("unknown --broker 'ib' (valid: paper, mt5-sim)")

        val (fetcher, _) = research(strategy(dir), "--fetcher", "ftp")
        assertThat(fetcher.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(fetcher.err).contains("unsupported --fetcher 'ftp' (supported: dukascopy)")
    }

    @Test
    fun `missing days refuse the session and allowed-incomplete days with no ticks say so`() {
        val (missing, _) = research(strategy(dir), fetcher = nothing)
        assertThat(missing.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(missing.err).contains("qkt: error: ").contains("XAUUSD")

        val (empty, _) = research(strategy(dir), "--allow-incomplete", fetcher = nothing)
        assertThat(empty.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(empty.err).contains("qkt: error: no ticks for [XAUUSD]").contains(dir.resolve("data").toString())
    }

    @Test
    fun `a dukascopy script fetcher is accepted while the injected fetcher serves the run`() {
        val script = dir.resolve("fetch.py").also { Files.writeString(it, "# never run\n") }
        val (run, repl) =
            research(
                strategy(dir),
                "--fetcher",
                "dukascopy",
                "--fetcher-script",
                dir.resolve("fetch.py").toString(),
            )

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(repl).startsWith("loaded ").contains("ticks for [XAUUSD]")
    }
}
