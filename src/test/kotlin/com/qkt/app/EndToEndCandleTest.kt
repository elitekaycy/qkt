package com.qkt.app

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.CandleEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndCandleTest : EndToEndFixtures() {
    @Test
    fun `tick stream spanning a window boundary produces a CandleEvent`() {
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val captured = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { captured.add(it.candle) }
        wirePipeline(emptyList())

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 0L))
        engine.onTick(Tick("XAUUSD", Money.of("2401.0"), 30_000L))
        engine.onTick(Tick("XAUUSD", Money.of("2402.0"), 75_000L))

        assertThat(captured).hasSize(1)
        assertThat(captured[0].symbol).isEqualTo("XAUUSD")
        assertThat(captured[0].startTime).isEqualTo(0L)
    }

    @Test
    fun `strategy receiving onCandle can emit a signal that fills`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val candleStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                }

                override fun onCandle(
                    candle: Candle,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy(candle.symbol, Money.of("1")))
                }
            }
        wirePipeline(listOf(candleStrategy))

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 30_000L))
        engine.onTick(Tick("XAUUSD", Money.of("2400.5"), 75_000L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].side).isEqualTo(Side.BUY)
    }

    @Test
    fun `aggregator subscribes before strategies see the same tick`() {
        val sequence = mutableListOf<String>()
        val orderingStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    sequence.add("onTick(${tick.timestamp})")
                }

                override fun onCandle(
                    candle: Candle,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    sequence.add("onCandle(${candle.startTime})")
                }
            }
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        wirePipeline(listOf(orderingStrategy))

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 30_000L))
        engine.onTick(Tick("XAUUSD", Money.of("2401.0"), 75_000L))

        assertThat(sequence).containsExactly(
            "onTick(30000)",
            "onCandle(0)",
            "onTick(75000)",
        )
    }
}
