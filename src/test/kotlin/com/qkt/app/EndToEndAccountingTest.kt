package com.qkt.app

import com.qkt.common.Money
import com.qkt.events.TradeEvent
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndAccountingTest : EndToEndFixtures() {
    @Test
    fun `position tracker is updated before subsequent FILLED log read`() {
        val seenPositions = mutableListOf<BigDecimal?>()
        val strategy = buyEveryTick("XAUUSD")
        wirePipeline(listOf(strategy))
        bus.subscribe<TradeEvent> { e ->
            seenPositions.add(positions.positionFor(e.trade.symbol)?.quantity)
        }

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(seenPositions).hasSize(1)
        assertThat(seenPositions[0]).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `realized PnL accumulates after a closing trade`() {
        val sellAfterBuy =
            object : Strategy {
                private var bought = false

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (!bought) {
                        emit(Signal.Buy("XAUUSD", Money.of("1")))
                        bought = true
                    } else {
                        emit(Signal.Sell("XAUUSD", Money.of("1")))
                    }
                }
            }
        wirePipeline(listOf(sellAfterBuy))

        engine.onTick(Tick("XAUUSD", Money.of("100"), 999L))
        engine.onTick(Tick("XAUUSD", Money.of("120"), 1000L))

        assertThat(trades).hasSize(2)
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `unrealized PnL is visible after an open position`() {
        val buyOnce =
            object : Strategy {
                private var done = false

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (!done) {
                        emit(Signal.Buy("XAUUSD", Money.of("2")))
                        done = true
                    }
                }
            }
        wirePipeline(listOf(buyOnce))

        engine.onTick(Tick("XAUUSD", Money.of("100"), 999L))
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.ZERO)

        engine.onTick(Tick("XAUUSD", Money.of("110"), 1000L))
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.of("20"))
    }
}
