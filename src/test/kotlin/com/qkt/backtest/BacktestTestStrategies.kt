package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext

/** Tick builder and scripted buy/sell strategies for the core backtest tests. */
internal object BacktestTestStrategies {
    fun tick(
        symbol: String,
        price: String,
        ts: Long,
    ) = Tick(symbol, Money.of(price), ts)

    fun buyEveryTickStrategy(
        symbol: String,
        size: String,
    ) = object : Strategy {
        override fun onTick(
            t: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            emit(Signal.Buy(symbol, Money.of(size)))
        }
    }

    fun buyThenSellStrategy(
        symbol: String,
        size: String,
    ) = object : Strategy {
        private var step = 0

        override fun onTick(
            t: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            when (step++) {
                0 -> emit(Signal.Buy(symbol, Money.of(size)))
                1 -> emit(Signal.Sell(symbol, Money.of(size)))
            }
        }
    }

    fun cyclingStrategy(symbol: String) =
        object : Strategy {
            private var long = false

            override fun onTick(
                t: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (!long) {
                    emit(Signal.Buy(symbol, Money.of("1")))
                } else {
                    emit(Signal.Sell(symbol, Money.of("1")))
                }
                long = !long
            }
        }
}
