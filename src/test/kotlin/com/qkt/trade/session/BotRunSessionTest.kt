package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotRunSessionTest {
    private fun ticks(): List<Tick> =
        (0 until 6).flatMap { bar ->
            val base = bar * 60_000L
            listOf(
                Tick("XAUUSD", BigDecimal(2400 + bar), base + 1_000L),
                Tick("XAUUSD", BigDecimal(2401 + bar), base + 30_000L),
            )
        } + Tick("XAUUSD", BigDecimal("2410"), 360_000L + 1_000L)

    private fun session(identities: List<String> = listOf("brain")): BotRunSession {
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val bridges = identities.associateWith { BotBridgeStrategy() }
        val engine =
            Backtest(
                strategies = bridges.map { (id, b) -> id to b } + (BotSessionRecorder.ID to recorder),
                ticks = ticks(),
                candleWindow = TimeWindow.parse("1m"),
                startingBalance = BigDecimal("10000"),
            ).toEngine()
        return BotRunSession(
            runId = "test-run",
            backend = ReplayBotRunBackend(engine),
            bridges = bridges,
            history = history,
            recorder = recorder,
        )
    }

    @Test
    fun `next returns consecutive closed bars and advances the sim clock`() {
        val s = session()
        val first = s.next("XAUUSD") ?: error("expected a bar")
        val second = s.next("XAUUSD") ?: error("expected a bar")
        assertThat(first.startTime).isEqualTo(0L)
        assertThat(second.startTime).isEqualTo(60_000L)
    }

    @Test
    fun `next returns null when the window is exhausted`() {
        val s = session()
        var bars = 0
        while (s.next("XAUUSD") != null) bars++
        assertThat(bars).isGreaterThanOrEqualTo(5)
        assertThat(s.next("XAUUSD")).isNull()
    }

    @Test
    fun `bars never serves past sim-now and quote reflects the cursor`() {
        val s = session()
        s.next("XAUUSD")
        s.next("XAUUSD")
        val served = s.bars("XAUUSD", 10)
        assertThat(served).hasSize(2)
        assertThat(served.last().startTime).isEqualTo(60_000L)
        val quote = s.quote("XAUUSD") ?: error("expected a quote")
        assertThat(quote.timestamp).isLessThanOrEqualTo(s.simNowMs())
    }

    @Test
    fun `submitted buy flows through the pipeline and lands in the result tagged with its identity`() {
        val s = session()
        s.next("XAUUSD")
        s.submit("brain", Signal.Buy("XAUUSD", BigDecimal("1")))
        s.next("XAUUSD")
        s.next("XAUUSD")
        val result = s.finish() ?: error("backtest finish must return a result")
        assertThat(result.trades).isNotEmpty()
        assertThat(result.trades.map { it.strategyId }.distinct()).containsExactly("brain")
    }

    @Test
    fun `undeclared identity is rejected fail-closed`() {
        val s = session()
        assertThatThrownBy { s.submit("ghost", Signal.Buy("XAUUSD", BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("brain")
    }
}
