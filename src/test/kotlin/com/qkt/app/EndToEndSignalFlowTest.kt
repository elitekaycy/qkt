package com.qkt.app

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndSignalFlowTest : EndToEndFixtures() {
    @Test
    fun `single strategy buy on every tick produces a fill`() {
        wirePipeline(listOf(buyEveryTick("XAUUSD")))

        engine.onTick(Tick("XAUUSD", Money.of("2400.5"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].side).isEqualTo(Side.BUY)
        assertThat(trades[0].price).isEqualByComparingTo(Money.of("2400.5"))
    }

    @Test
    fun `signal for unknown symbol produces no fill`() {
        val emitForUnknown =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("BTCUSD", Money.of("1")))
                }
            }
        wirePipeline(listOf(emitForUnknown))

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(trades).isEmpty()
    }

    @Test
    fun `multiple strategies all see the same tick`() {
        val seenByA = mutableListOf<Tick>()
        val seenByB = mutableListOf<Tick>()
        val a =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seenByA.add(tick)
                }
            }
        val b =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seenByB.add(tick)
                }
            }
        wirePipeline(listOf(a, b))

        val tick = Tick("XAUUSD", Money.of("2400.0"), 999L)
        engine.onTick(tick)

        assertThat(seenByA).containsExactly(tick)
        assertThat(seenByB).containsExactly(tick)
    }

    @Test
    fun `order ids are sequential across multiple signals`() {
        val emitTwo =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", Money.of("1")))
                    emit(Signal.Sell("XAUUSD", Money.of("1")))
                }
            }
        wirePipeline(listOf(emitTwo), captureOrders = true)

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(orders.map { it.id }).containsExactly("ORD-0", "ORD-1")
    }

    @Test
    fun `multiple signals from one tick all fill at same tracker price`() {
        val emitTwoBuys =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", Money.of("1")))
                    emit(Signal.Buy("XAUUSD", Money.of("2")))
                }
            }
        wirePipeline(listOf(emitTwoBuys))

        engine.onTick(Tick("XAUUSD", Money.of("2400.5"), 999L))

        assertThat(trades).hasSize(2)
        assertThat(trades[0].price).isEqualByComparingTo(Money.of("2400.5"))
        assertThat(trades[1].price).isEqualByComparingTo(Money.of("2400.5"))
    }

    @Test
    fun `cross-symbol strategy emits signal for symbol B from tick of symbol A`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        val watchEurTradeGold =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (tick.symbol == "EURUSD") emit(Signal.Buy("XAUUSD", Money.of("1")))
                }
            }
        wirePipeline(listOf(watchEurTradeGold))

        engine.onTick(Tick("EURUSD", Money.of("1.0921"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].price).isEqualByComparingTo(Money.of("2400.0"))
    }
}
