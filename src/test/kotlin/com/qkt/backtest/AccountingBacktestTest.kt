package com.qkt.backtest

import com.qkt.accounting.AccountingConfig
import com.qkt.common.Money
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountingBacktestTest {
    private val symbol = "USDJPY"

    @Test
    fun `USDJPY realized pnl is booked in account currency not raw JPY`() {
        val result =
            Backtest(
                strategies = listOf("s" to roundTripStrategy()),
                ticks =
                    listOf(
                        Tick(symbol, Money.of("150"), 1_000L),
                        Tick(symbol, Money.of("151"), 2_000L),
                    ),
                initialTimestamp = 1_000L,
                startingBalance = Money.of("10000"),
                instruments = StandardInstrumentRegistry,
            ).run()

        assertThat(result.global.realizedTotal).isEqualByComparingTo("0.66225166")
        assertThat(result.global.totalPnL).isEqualByComparingTo("0.66225166")
        val close = result.trades.last()
        assertThat(close.nativeRealized).isEqualByComparingTo("100.00000000")
        assertThat(close.nativeCurrency).isEqualTo("JPY")
        assertThat(close.accountRealized).isEqualByComparingTo("0.66225166")
        assertThat(close.accountCurrency).isEqualTo("USD")
        assertThat(close.fxRate).isEqualByComparingTo("0.006622516556291391")
        assertThat(
            result.accounting!!
                .conversions
                .single()
                .source,
        ).isEqualTo("context:USDJPY")
    }

    @Test
    fun `configured FX series converts cross-pair pnl on the replay path`() {
        val traded = "EURJPY"
        val result =
            Backtest(
                strategies = listOf("s" to roundTripStrategy(traded)),
                ticks =
                    listOf(
                        Tick("USDJPY", Money.of("151"), 900L),
                        Tick(traded, Money.of("160"), 1_000L),
                        Tick("USDJPY", Money.of("151"), 1_500L),
                        Tick(traded, Money.of("161"), 2_000L),
                    ),
                initialTimestamp = 900L,
                startingBalance = Money.of("10000"),
                instruments = StandardInstrumentRegistry,
                accountingConfig = AccountingConfig(symbols = mapOf("USDJPY" to "USDJPY")),
                tradedSymbols = listOf(traded),
            ).run()

        assertThat(result.global.realizedTotal).isEqualByComparingTo("0.66225166")
        assertThat(result.trades.last().nativeCurrency).isEqualTo("JPY")
        assertThat(result.trades.last().fxSource).isEqualTo("market:USDJPY")
    }

    @Test
    fun `USDJPY unrealized pnl is converted at the mark price`() {
        val result =
            Backtest(
                strategies = listOf("s" to buyOnceStrategy()),
                ticks =
                    listOf(
                        Tick(symbol, Money.of("150"), 1_000L),
                        Tick(symbol, Money.of("151"), 2_000L),
                    ),
                initialTimestamp = 1_000L,
                startingBalance = Money.of("10000"),
                instruments = StandardInstrumentRegistry,
            ).run()

        assertThat(result.global.realizedTotal).isEqualByComparingTo("0.00000000")
        assertThat(result.global.unrealizedTotal).isEqualByComparingTo("0.66225166")
        assertThat(result.global.totalPnL).isEqualByComparingTo("0.66225166")
    }

    private fun roundTripStrategy(tradedSymbol: String = symbol): Strategy =
        object : Strategy {
            private var step = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (tick.symbol != tradedSymbol) return
                when (step++) {
                    0 -> emit(Signal.Buy(tradedSymbol, Money.of("0.001")))
                    1 -> emit(Signal.Sell(tradedSymbol, Money.of("0.001")))
                }
            }
        }

    private fun buyOnceStrategy(): Strategy =
        object : Strategy {
            private var bought = false

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (!bought) {
                    bought = true
                    emit(Signal.Buy(symbol, Money.of("0.001")))
                }
            }
        }
}
