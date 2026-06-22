package com.qkt.cli

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.store.BinaryBarStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataBuildBarsTest {
    private fun dayTicks(day: LocalDate): List<Tick> {
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return (0 until 1440).map { m ->
            val mid = 1850.0 + 5.0 * sin(m / 50.0)
            Tick("XAUUSD", Money.of("%.3f".format(mid)), start + m * 60_000L)
        }
    }

    private fun aggregate(
        ticks: List<Tick>,
        tf: TimeWindow,
    ): List<Candle> {
        val out = ArrayList<Candle>()
        val agg = CandleAggregator.standalone(tf) { out.add(it) }
        ticks.forEach { agg.onTick(it) }
        agg.flushClosed(Long.MAX_VALUE)
        return out
    }

    @Test
    fun `build-bars aggregates the tick store into binary bars`(
        @TempDir dir: Path,
    ) {
        val day = LocalDate.parse("2024-01-04")
        val ticks = dayTicks(day)
        val dataRoot = dir.resolve("data")
        val tickFile = dataRoot.resolve("symbols").resolve("XAUUSD").resolve("$day.bin")
        Files.createDirectories(tickFile.parent)
        BinaryTickWriter().write(tickFile, "XAUUSD", ticks)

        val code =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        "XAUUSD",
                        "--tf",
                        "15m",
                        "--from",
                        "2024-01-04",
                        "--to",
                        "2024-01-05",
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        val tf = TimeWindow.parse("15m")
        val expected = aggregate(ticks, tf).map { it.copy(symbol = "BACKTEST:XAUUSD") }
        assertThat(BinaryBarStore(dataRoot).readDay("BACKTEST", "XAUUSD", tf, day)).isEqualTo(expected)
        assertThat(expected.size).isGreaterThan(0)
    }
}
