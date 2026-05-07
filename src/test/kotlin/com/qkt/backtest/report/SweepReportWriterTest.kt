package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepReportWriterTest {
    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun ticks(): List<Tick> = (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    private fun bt(label: String): Backtest =
        Backtest(
            strategies = listOf(label to noopStrategy),
            ticks = ticks(),
            candleWindow = TimeWindow.ONE_MINUTE,
            cadence = SampleCadence.CANDLE_CLOSE,
        )

    @Test
    fun `writer produces summary csv and json plus per-run dirs`(
        @TempDir dir: Path,
    ) {
        val sweep =
            BacktestSweep(
                configs = listOf("ema_9_21" to "fast=9_slow=21", "ema_12_26" to "fast=12_slow=26"),
                backtestFactory = { label, _ -> bt(label) },
            )
        val result = sweep.run()

        SweepReportWriter(dir).write(result)

        assertThat(dir.resolve("sweep_summary.csv")).exists()
        assertThat(dir.resolve("sweep_summary.json")).exists()
        assertThat(dir.resolve("runs/ema_9_21/result.json")).exists()
        assertThat(dir.resolve("runs/ema_12_26/result.json")).exists()
        assertThat(dir.resolve("runs/ema_9_21/equity_global.csv")).exists()
        assertThat(dir.resolve("runs/ema_9_21/trades.csv")).exists()

        val csv = Files.readString(dir.resolve("sweep_summary.csv"))
        val lines = csv.trim().lines()
        assertThat(lines.size).isEqualTo(3)
        assertThat(lines[0])
            .isEqualTo("label,config,totalPnL,sharpeRatio,maxDrawdown,winRate,tradeCount,profitFactor,calmarRatio")
        assertThat(lines[1]).startsWith("ema_9_21,")
        assertThat(lines[2]).startsWith("ema_12_26,")

        val json = Files.readString(dir.resolve("sweep_summary.json"))
        assertThat(json).startsWith("[")
        assertThat(json).contains("\"label\": \"ema_9_21\"")
        assertThat(json).contains("\"config\": \"fast=9_slow=21\"")
    }

    @Test
    fun `unsafe label rejected before any file written`(
        @TempDir dir: Path,
    ) {
        val sweep =
            BacktestSweep(
                configs = listOf("../danger" to "ok"),
                backtestFactory = { label, _ -> bt(label) },
            )
        val result = sweep.run()

        assertThatThrownBy { SweepReportWriter(dir).write(result) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }

    @Test
    fun `config containing a comma is rejected`(
        @TempDir dir: Path,
    ) {
        val sweep =
            BacktestSweep(
                configs = listOf("ok" to "embeds,comma,unsafe"),
                backtestFactory = { label, _ -> bt(label) },
            )
        val result = sweep.run()

        assertThatThrownBy { SweepReportWriter(dir).write(result) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("comma")
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }
}
