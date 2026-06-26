package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExecutionSimulationBacktestTest {
    private val symbol = "EXNESS:XAUUSD"

    private fun registry(): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? =
                if (qktSymbol == symbol) {
                    InstrumentMeta(
                        qktSymbol = symbol,
                        contractSize = BigDecimal("100"),
                        volumeStep = BigDecimal("0.01"),
                        volumeMin = BigDecimal("0.01"),
                        volumeMax = null,
                        pointSize = BigDecimal("0.001"),
                        digits = 3,
                        tradeStopsLevelPoints = 50,
                        slippagePoints = 2,
                    )
                } else {
                    null
                }
        }

    private fun buyOnFirstTick(): Strategy =
        object : Strategy {
            private var fired = false

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (!fired) {
                    fired = true
                    emit(Signal.Buy(tick.symbol, Money.of("0.10")))
                }
            }
        }

    private fun ticks(): List<Tick> =
        listOf(
            Tick(symbol = symbol, price = Money.of("2000.000"), timestamp = 0L),
            Tick(symbol = symbol, price = Money.of("2001.000"), timestamp = 1_000L),
            Tick(symbol = symbol, price = Money.of("2002.000"), timestamp = 2_000L),
        )

    @Test
    fun `mt5 realistic latency fills against the delayed event-time tick`() {
        val execution =
            ExecutionSimulationConfig
                .defaultsFor(ExecutionPreset.MT5_REALISTIC, seed = 7L)
                .copy(latencyMs = 1_000L, slippage = SlippageSpec.ZERO)

        val result =
            Backtest(
                strategies = listOf("s" to buyOnFirstTick()),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments = registry(),
                brokerKind = execution.brokerKind,
                executionConfig = execution,
            ).run()

        assertThat(result.trades).hasSize(1)
        val trade = result.trades.single().trade
        assertThat(trade.timestamp).isEqualTo(1_000L)
        assertThat(trade.price).isGreaterThan(Money.of("2001.000"))
    }

    @Test
    fun `fixed seed stress execution is deterministic`() {
        val execution = ExecutionSimulationConfig.defaultsFor(ExecutionPreset.STRESS, seed = 42L)

        fun run(): List<String> =
            Backtest(
                strategies = listOf("s" to buyOnFirstTick()),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments = registry(),
                brokerKind = execution.brokerKind,
                executionConfig = execution,
            ).run()
                .trades
                .map {
                    "${it.trade.timestamp}:${it.trade.quantity.toPlainString()}:${it.trade.price.toPlainString()}"
                }

        assertThat(run()).isEqualTo(run())
    }

    @Test
    fun `fixed seed mt5 realistic execution is deterministic`() {
        val execution =
            ExecutionSimulationConfig
                .defaultsFor(ExecutionPreset.MT5_REALISTIC, seed = 42L)
                .copy(latencyMs = 1_000L)

        fun run(): List<String> =
            Backtest(
                strategies = listOf("s" to buyOnFirstTick()),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments = registry(),
                brokerKind = execution.brokerKind,
                executionConfig = execution,
            ).run()
                .trades
                .map {
                    "${it.trade.timestamp}:${it.trade.quantity.toPlainString()}:${it.trade.price.toPlainString()}"
                }

        assertThat(run()).isEqualTo(run())
    }
}
