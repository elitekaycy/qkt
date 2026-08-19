package com.qkt.trade.session

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BotBridgeStrategyTest {
    private val tick =
        Tick(symbol = "XAUUSD", price = BigDecimal("2400"), timestamp = 1_000L)

    private fun context(): StrategyContext = testStrategyContext()

    @Test
    fun `drains queued signals in order on the next tick`() {
        val bridge = BotBridgeStrategy()
        val buy = Signal.Buy("XAUUSD", BigDecimal("0.01"))
        val sell = Signal.Sell("XAUUSD", BigDecimal("0.02"))
        bridge.submit(buy)
        bridge.submit(sell)
        val emitted = mutableListOf<Signal>()
        bridge.onTick(tick, context()) { emitted.add(it) }
        assertThat(emitted).containsExactly(buy, sell)
        emitted.clear()
        bridge.onTick(tick, context()) { emitted.add(it) }
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `recorder captures closed candles into history and tracks the last tick`() {
        val history = BarHistory(capacity = 4)
        val recorder = BotSessionRecorder(history)
        recorder.onTick(tick, context()) {}
        val candle =
            Candle(
                symbol = "XAUUSD",
                open = BigDecimal("1"),
                high = BigDecimal("2"),
                low = BigDecimal("0.5"),
                close = BigDecimal("1.5"),
                volume = BigDecimal.ONE,
                startTime = 0L,
                endTime = 60_000L,
            )
        recorder.onCandle(candle, context()) {}
        assertThat(history.last("XAUUSD", 1)).containsExactly(candle)
        assertThat(recorder.lastTick("XAUUSD")).isEqualTo(tick)
        assertThat(recorder.lastTick("EURUSD")).isNull()
    }
}
