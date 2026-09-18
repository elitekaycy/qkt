package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.report.BacktestReportFixtures.ticks
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestReportStrategyIdTest {
    @Test
    fun `unsafe strategyId rejected before any file written`(
        @TempDir dir: Path,
    ) {
        val noopStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val backtest =
            Backtest(
                strategies = listOf("../danger" to noopStrategy),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        val result = backtest.run()

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BacktestReportWriter(dir).write(result)
        }
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }

    @Test
    fun `portfolio strategy ids are encoded in equity filenames`(
        @TempDir dir: Path,
    ) {
        val noopStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val result =
            Backtest(
                strategies = listOf("book:child" to noopStrategy),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        BacktestReportWriter(dir).write(result)

        assertThat(dir.resolve("equity_book%3Achild.csv")).exists()
        assertThat(Files.readString(dir.resolve("result.json"))).contains("\"book:child\"")
    }
}
