package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.evidence.ExecutionEvidence
import com.qkt.instrument.YamlInstrumentRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestExecutionEvidenceTest {
    private fun registry(dir: Path): YamlInstrumentRegistry {
        val file = dir.resolve("instruments.yaml")
        val common = "volumeStep: 0.01, volumeMin: 0.01, digits: 3, tradeStopsLevelPoints: 0"
        Files.writeString(
            file,
            "instruments:\n" +
                "  - { qktSymbol: EXNESS:XAUUSD, contractSize: 100, pointSize: 0.001, $common, spreadPoints: 260 }\n" +
                "  - { qktSymbol: EXNESS:XAGUSD, contractSize: 5000, pointSize: 0.001, $common, " +
                "minSpreadPoints: 20 }\n" +
                "  - { qktSymbol: EXNESS:BTCUSD, contractSize: 1, pointSize: 0.01, $common }\n",
        )
        return YamlInstrumentRegistry.load(file)
    }

    @Test
    fun `the evidence names each traded symbol's spread model under mt5-sim`(
        @TempDir dir: Path,
    ) {
        val symbols = listOf("EXNESS:XAUUSD", "EXNESS:XAGUSD", "EXNESS:BTCUSD")
        val base = ExecutionEvidence(preset = "mt5-basic", broker = "mt5-sim", fillPriceSource = "bid/ask")

        val evidence = base.withSpreadModels(spreadModels(registry(dir), symbols, BrokerKind.MT5_SIM))

        assertThat(evidence.fillPriceSource)
            .isEqualTo("bid/ask; instruments.yaml spread: EXNESS:XAGUSD min 20 points, EXNESS:XAUUSD fixed 260 points")
    }

    @Test
    fun `paper fills ignore a stated spread, so the evidence names none`(
        @TempDir dir: Path,
    ) {
        assertThat(spreadModels(registry(dir), listOf("EXNESS:XAUUSD"), BrokerKind.PAPER)).isEmpty()
    }
}
