package com.qkt.cli.fetch

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.LocalBarStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RollsFetchFailuresTest {
    @TempDir
    lateinit var dir: Path

    private val target = "BINANCE_UM:BTCUSDT"

    private fun instruments(roll: String = ", roll: { daysBeforeExpiry: 8, atUtc: '08:00', adjust: panama }") =
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "futures:\n  - { root: $target, currency: USDT, multiplier: 1, tickSize: 0.1, volumeStep: 0.001, " +
                "volumeMin: 0.001$roll }\n",
        )

    private fun rolls(timeframe: String = "1m") =
        captureCliRun {
            RollsFetch.run(target, dir, null, TimeWindow.parse(timeframe), 0L) { _, _ -> error("no fetch expected") }
        }

    private fun forArgs(vararg argv: String) =
        captureCliRun {
            RollsFetch.forArgs(
                target,
                "BINANCE_UM",
                Args(arrayOf("fetch", target, "--rolls", *argv, "--data-root", dir.toString())),
            )
        }

    @Test
    fun `an undeclared root a root without a roll policy and a root without a catalog cannot be measured`() {
        Files.writeString(dir.resolve("instruments.yaml"), "futures: []\n")
        val undeclared = rolls()
        assertThat(undeclared.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(undeclared.err.trim())
            .isEqualTo(
                "qkt: could not build the roll history for $target: $target is not declared under 'futures:' in instruments.yaml",
            )

        instruments(roll = "")
        assertThat(rolls().err).contains("$target has no roll policy (add roll: to its futures entry)")

        instruments()
        assertThat(rolls().err).contains("no catalog for $target; run qkt fetch $target --catalog")
    }

    @Test
    fun `bars coarser than a day cannot price a roll`() {
        val run = rolls(timeframe = "2d")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("--rolls prices rolls from bars of 1d or shorter, not 2d")
    }

    @Test
    fun `an unreadable instruments file is reported as a failure`() {
        Files.createDirectories(dir.resolve("instruments.yaml"))

        val run = rolls()

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).startsWith("qkt: could not build the roll history for $target: ")
    }

    @Test
    fun `the command line timeframe is validated and a missing instruments file fails`() {
        val bad = forArgs("--tf", "fortnight")
        assertThat(bad.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(bad.err).contains("qkt: invalid --tf 'fortnight'")

        val missing = forArgs("--tf", "1d")
        assertThat(missing.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(missing.err).contains("could not build the roll history for $target")
    }

    @Test
    fun `one fetched day is stored and an empty one is left alone`() {
        val store = LocalBarStore(dir)
        val day = LocalDate.parse("2026-09-19")
        val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val bar =
            Candle(
                "BINANCE_UM:BTCUSDT_260925",
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.ONE,
                start,
                start + 60_000L,
            )

        RollsFetch.fetchOneDay({ _, _, _ -> listOf(bar) }, store, "BINANCE_UM", "BTCUSDT_260925", day)
        RollsFetch.fetchOneDay({ _, _, _ -> emptyList() }, store, "BINANCE_UM", "BTCUSDT_261225", day)

        assertThat(store.readDay("BINANCE_UM", "BTCUSDT_260925", "1m", day)).containsExactly(bar)
        assertThat(store.hasDay("BINANCE_UM", "BTCUSDT_261225", "1m", day)).isFalse()
    }
}
