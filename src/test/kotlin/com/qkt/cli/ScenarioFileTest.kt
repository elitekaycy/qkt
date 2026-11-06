package com.qkt.cli

import com.qkt.backtest.BrokerKind
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScenarioFileTest {
    @TempDir
    lateinit var tmp: Path

    private fun file(
        name: String,
        body: String,
    ): Path = tmp.resolve(name).also { Files.writeString(it, body.trimIndent()) }

    private fun scenarios(yaml: String) = ScenarioFile.load(file("scenarios.yaml", yaml))

    @Test
    fun `an entry may carry its own strategy broker instruments and balance`() {
        val strategy =
            file(
                "variant.qkt",
                "STRATEGY v VERSION 1\nSYMBOLS\n    gold = BACKTEST:XAUUSD EVERY 1m\nRULES\n    WHEN gold.close > 0 THEN BUY gold SIZING 0.1\n",
            )
        val instruments =
            file(
                "instruments.yaml",
                """
                instruments:
                  - qktSymbol: BACKTEST:XAUUSD
                    contractSize: 100
                    volumeStep: 0.01
                    volumeMin: 0.01
                    volumeMax: 50
                    pointSize: 0.001
                    digits: 3
                    tradeStopsLevelPoints: 0
                """,
            )

        val spec =
            scenarios(
                "- label: full\n  strategy: $strategy\n  broker: paper\n  instruments: $instruments\n  startingBalance: 2500\n",
            ).single()

        assertThat(spec.label).isEqualTo("full")
        assertThat(spec.params).isEmpty()
        assertThat(spec.ast?.name).isEqualTo("v")
        assertThat(spec.brokerKind).isEqualTo(BrokerKind.PAPER)
        assertThat(spec.instruments?.lookup("BACKTEST:XAUUSD")?.contractSize).isEqualByComparingTo("100")
        assertThat(spec.startingBalance).isEqualByComparingTo("2500")
    }

    @Test
    fun `a strategy that does not parse and an unknown broker are refused naming the entry`() {
        val broken = file("broken.qkt", "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")

        assertThatThrownBy { scenarios("- label: a\n  strategy: $broken\n") }
            .hasMessage("scenarios file entry 0: failed to parse strategy $broken")
        assertThatThrownBy { scenarios("- label: a\n- label: b\n  broker: ib\n") }
            .hasMessage("scenarios file entry 1: unknown broker 'ib' (valid: paper, mt5-sim)")
    }

    @Test
    fun `the file must be a non-empty list of labelled maps`() {
        assertThatThrownBy {
            ScenarioFile.load(
                tmp.resolve("absent.yaml"),
            )
        }.hasMessageContaining("scenarios file not found")
        assertThatThrownBy { scenarios("label: a\n") }.hasMessageContaining("top-level must be a list")
        assertThatThrownBy { scenarios("[]\n") }.hasMessage("scenarios file: list is empty")
        assertThatThrownBy { scenarios("- just a string\n") }.hasMessage("scenarios file: entry 0 must be a map")
        assertThatThrownBy {
            scenarios(
                "- params: { fast: 2 }\n",
            )
        }.hasMessage("scenarios file: entry 0 missing 'label'")
    }
}
