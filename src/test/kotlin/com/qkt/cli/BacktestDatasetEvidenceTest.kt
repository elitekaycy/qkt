package com.qkt.cli

import com.qkt.cli.BacktestContext.Companion.SetupError
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `--dataset` is refused unless the snapshot reads, covers the run's symbol and days, and still matches its files. */
class BacktestDatasetEvidenceTest {
    @TempDir
    lateinit var tmp: Path

    private val dataRoot get() = tmp.resolve("data")
    private val day = dataRoot.resolve("symbols/XAUUSD/2024-01-01.csv")
    private val from = Instant.parse("2024-01-01T00:00:00Z")
    private val to = Instant.parse("2024-01-02T00:00:00Z")

    private fun snapshot(): Path {
        Files.createDirectories(day.parent)
        Files.writeString(
            day,
            "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n" +
                "1704067200000,XAUUSD,2000.0,1,1999.9,2000.1,1,1\n",
        )
        val out = tmp.resolve("xau-snapshot.json")
        val argv =
            arrayOf("data", "snapshot", "XAUUSD", "--from", "2024-01-01", "--to", "2024-01-02") +
                arrayOf("--data-root", dataRoot.toString(), "--out", out.toString())
        assertThat(captureCliRun { DataCommand(Args(argv)).run() }.code).isEqualTo(ExitCodes.SUCCESS)
        return out
    }

    private fun context(
        dataset: Path,
        symbols: List<String> = listOf("BACKTEST:XAUUSD"),
        runFrom: Instant = from,
    ) = BacktestDatasetEvidence.datasetContext(
        Args(arrayOf("backtest", "s.qkt", "--dataset", dataset.toString(), "--data-root", dataRoot.toString())),
        emptyList(),
        symbols,
        runFrom,
        to,
    )

    @Test
    fun `a matching snapshot pins its id and data root`() {
        val dataset = snapshot()

        val context = context(dataset)

        assertThat(context.evidence.id).isNotBlank()
        assertThat(context.evidence.hash).startsWith("sha256:")
        assertThat(context.evidence.qualityPolicy).isEqualTo("strict")
        assertThat(context.evidence.mutableStore).isFalse()
        assertThat(
            Path.of(context.dataRoot!!).toAbsolutePath().normalize(),
        ).isEqualTo(dataRoot.toAbsolutePath().normalize())
    }

    @Test
    fun `an unreadable snapshot file is refused naming it`() {
        val dataset = Files.writeString(tmp.resolve("broken.json"), "not json")

        assertThatThrownBy { context(dataset) }
            .isInstanceOf(SetupError::class.java)
            .hasMessageStartingWith("cannot read --dataset $dataset: ")
    }

    @Test
    fun `a snapshot of another symbol is refused`() {
        val dataset = snapshot()

        assertThatThrownBy { context(dataset, symbols = listOf("BACKTEST:EURUSD")) }
            .isInstanceOf(SetupError::class.java)
            .hasMessage("--dataset $dataset covers XAUUSD, but run symbols are [EURUSD]")
    }

    @Test
    fun `a run reaching outside the snapshot's days is refused`() {
        val dataset = snapshot()

        assertThatThrownBy { context(dataset, runFrom = Instant.parse("2023-12-31T00:00:00Z")) }
            .isInstanceOf(SetupError::class.java)
            .hasMessageStartingWith("--dataset $dataset covers 2024-01-01..")
            .hasMessageContaining("but run needs 2023-12-31..2024-01-02")
    }

    @Test
    fun `a stored file changed since the snapshot fails verification`() {
        val dataset = snapshot()
        Files.writeString(day, Files.readString(day) + "1704067260000,XAUUSD,2001.0,1,2000.9,2001.1,1,1\n")

        assertThatThrownBy { context(dataset) }
            .isInstanceOf(SetupError::class.java)
            .hasMessageStartingWith("dataset snapshot verification failed:\n  ")
    }
}
