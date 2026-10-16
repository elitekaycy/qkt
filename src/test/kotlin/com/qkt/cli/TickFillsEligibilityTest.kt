package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.dsl.ast.StreamDecl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TickFillsEligibilityTest {
    private val paper = ExecutionSimulationConfig.forBrokerKind(BrokerKind.PAPER)
    private val mt5 = ExecutionSimulationConfig.forBrokerKind(BrokerKind.MT5_SIM)
    private val m15 = listOf(StreamDecl("x", "BACKTEST", "XAUUSD", "15m"))

    @Test
    fun `a single-timeframe paper run can resolve fills on ticks`() {
        assertThat(tickFillsBlocker(listOf("BACKTEST:XAUUSD"), m15, paper)).isNull()
    }

    @Test
    fun `latency, stop latency, mixed timeframes and continuous futures each name why they cannot`() {
        assertThat(tickFillsBlocker(listOf("BACKTEST:XAUUSD"), m15, mt5.copy(latencyMs = 50))).contains("latency")
        assertThat(tickFillsBlocker(listOf("BACKTEST:XAUUSD"), m15, mt5.copy(stopLatencyMs = 260))).contains("260ms")
        val mixed = m15 + StreamDecl("y", "BACKTEST", "XAUUSD", "1h")
        assertThat(tickFillsBlocker(listOf("BACKTEST:XAUUSD"), mixed, paper)).contains("mixed-timeframe")
        assertThat(tickFillsBlocker(listOf("CME:ES@front"), m15, paper)).contains("continuous futures")
    }
}
