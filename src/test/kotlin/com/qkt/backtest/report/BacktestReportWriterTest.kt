package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.ExecutionEvidence
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestReportWriterTest {
    private fun ticks(): List<Tick> = (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    @Test
    fun `writer produces result_json equity_csv trades_csv rejections_csv`(
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
                strategies = listOf("s1" to noopStrategy),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
            )
        val result = backtest.run().copy(evidence = evidence())

        BacktestReportWriter(dir).write(result)

        assertThat(dir.resolve("result.json")).exists()
        assertThat(dir.resolve("equity_global.csv")).exists()
        assertThat(dir.resolve("equity_s1.csv")).exists()
        assertThat(dir.resolve("trades.csv")).exists()
        assertThat(dir.resolve("rejections.csv")).exists()

        val json = Files.readString(dir.resolve("result.json"))
        assertThat(json).contains("\"cadence\": \"CANDLE_CLOSE\"")
        assertThat(json).contains("\"evidence\": {\"qktVersion\":\"test\"")
        assertThat(json).contains("\"strategyHash\":\"sha256:strategy\"")
        assertThat(json).contains("\"mutableStore\":true")
        assertThat(json).contains("\"accounting\": {\"accountCurrency\": \"USD\"")
        assertThat(json).contains("\"global\":")
        assertThat(json).contains("\"perStrategy\":")

        val eqCsv = Files.readString(dir.resolve("equity_global.csv"))
        assertThat(eqCsv.lines().first()).isEqualTo("timestamp,equity")
        val tradesCsv = Files.readString(dir.resolve("trades.csv"))
        assertThat(tradesCsv.lines().first())
            .contains("nativeRealized,nativeCurrency,accountRealized,accountCurrency,fxRate")
    }

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

    private fun evidence(): EvidenceEnvelope =
        EvidenceEnvelope(
            qktVersion = "test",
            gitSha = "abc123",
            buildTimestamp = "2026-06-25T00:00:00Z",
            command = listOf("backtest", "s.qkt"),
            strategyHash = "sha256:strategy",
            dataset = DatasetEvidence(mutableStore = true),
            execution = ExecutionEvidence(preset = "paper-fast", broker = "paper"),
        )
}
