package com.qkt.backtest

import com.qkt.backtest.TickResolvedParityFixtures.field
import com.qkt.backtest.TickResolvedParityFixtures.instrumentsFile
import com.qkt.backtest.TickResolvedParityFixtures.normalizedReport
import com.qkt.backtest.TickResolvedParityFixtures.runJson
import com.qkt.backtest.TickResolvedParityFixtures.seedTicks
import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The acceptance gate for tick-resolved fills (`--bars --tick-fills`): bars drive signals but fills
 * resolve on real ticks, so the result must be byte-identical to a full-tick replay. A volatile
 * intrabar path makes SL/TP both-hit and entry-trigger cases actually occur, which is exactly where
 * plain `--bars` drifts and tick-resolved must not.
 */
class TickResolvedParityTest {
    @Test
    fun `tick-resolved fills match a full-tick replay byte-for-byte`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedTicks(dataRoot, days = 5)
        // Build the 15m bar store the --bars tier drives off (ticks already seeded for slicing).
        val build =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "XAUUSD",
                        "--tf",
                        "15m",
                        "--from",
                        from,
                        "--to",
                        to,
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(build).isEqualTo(ExitCodes.SUCCESS)

        val fullTick = runJson(dir, dataRoot, from, to, extra = emptyList())
        val resolved = runJson(dir, dataRoot, from, to, extra = listOf("--bars", "--tick-fills"))

        // The whole point: a bracket strategy on a volatile path actually trades and hits brackets.
        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        // Complete semantic report parity. Only the invocation command differs by design because
        // one run includes --bars --tick-fills; no computed or model-evidence field is removed.
        assertThat(normalizedReport(resolved)).isEqualTo(normalizedReport(fullTick))
    }

    @Test
    fun `tick-resolved fills match a full-tick replay under mt5-sim`(
        @TempDir dir: Path,
    ) {
        val dataRoot = dir.resolve("data")
        val from = "2024-01-02"
        val to = "2024-01-07"
        seedTicks(dataRoot, days = 5)
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "build-bars",
                    "XAUUSD",
                    "--tf",
                    "15m",
                    "--from",
                    from,
                    "--to",
                    to,
                    "--data-root",
                    dataRoot.toString(),
                ),
            ),
        ).run().also { assertThat(it).isEqualTo(ExitCodes.SUCCESS) }

        val mt5 = listOf("--broker", "mt5-sim", "--instruments", instrumentsFile(dir).toString())
        val fullTick = runJson(dir, dataRoot, from, to, extra = mt5)
        val resolved = runJson(dir, dataRoot, from, to, extra = mt5 + listOf("--bars", "--tick-fills"))

        assertThat(field(fullTick, "trades").toInt()).isGreaterThan(0)
        assertThat(field(resolved, "trades")).isEqualTo(field(fullTick, "trades"))
        assertThat(field(resolved, "totalPnL")).isEqualTo(field(fullTick, "totalPnL"))
        assertThat(field(resolved, "maxDrawdown")).isEqualTo(field(fullTick, "maxDrawdown"))
    }
}
