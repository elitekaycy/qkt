package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestMt5SimTest {
    private fun registry(meta: InstrumentMeta): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == meta.qktSymbol) meta else null
        }

    private val xauusd =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.001"),
            digits = 3,
            tradeStopsLevelPoints = 0,
        )

    private fun buyOnFifthTick(): Strategy =
        object : Strategy {
            private var seen = 0

            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                seen += 1
                if (seen == 5) emit(Signal.Buy(tick.symbol, Money.of("0.157")))
            }
        }

    private fun ticksWithBidAsk(count: Int): List<Tick> =
        (1..count).map { i ->
            Tick(
                symbol = "EXNESS:XAUUSD",
                price = Money.of((2000 + i * 0.1).toString()),
                timestamp = i * 60_000L,
                bid = Money.of((2000 + i * 0.1 - 0.05).toString()),
                ask = Money.of((2000 + i * 0.1 + 0.05).toString()),
            )
        }

    @Test
    fun `MT5_SIM quantizes the strategy's requested quantity at fill time`() {
        val result =
            Backtest(
                strategies = listOf("s1" to buyOnFifthTick()),
                ticks = ticksWithBidAsk(8),
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments = registry(xauusd),
                brokerKind = BrokerKind.MT5_SIM,
            ).run()

        assertThat(result.trades).hasSize(1)
        // 0.157 → quantized DOWN to 0.15 (step 0.01).
        assertThat(
            result.trades
                .single()
                .trade.quantity,
        ).isEqualByComparingTo(Money.of("0.15"))
    }

    @Test
    fun `MT5_SIM market BUY fills at ask, not at mid`() {
        val result =
            Backtest(
                strategies = listOf("s1" to buyOnFifthTick()),
                ticks = ticksWithBidAsk(8),
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments = registry(xauusd),
                brokerKind = BrokerKind.MT5_SIM,
            ).run()

        assertThat(result.trades).hasSize(1)
        val trade = result.trades.single().trade
        assertThat(trade.side).isEqualTo(Side.BUY)
        // Tick #5 had ask = 2000.5 + 0.05 = 2000.55 (approximately).
        // Fill price should equal that ask, not the mid.
        assertThat(trade.price).isGreaterThan(Money.of("2000.50"))
        assertThat(trade.price).isLessThan(Money.of("2000.60"))
    }

    @Test
    fun `MT5_SIM and PAPER produce different fill prices for the same ticks`() {
        val resultPaper =
            Backtest(
                strategies = listOf("s1" to buyOnFifthTick()),
                ticks = ticksWithBidAsk(8),
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments = registry(xauusd),
                brokerKind = BrokerKind.PAPER,
            ).run()
        val resultSim =
            Backtest(
                strategies = listOf("s1" to buyOnFifthTick()),
                ticks = ticksWithBidAsk(8),
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments = registry(xauusd),
                brokerKind = BrokerKind.MT5_SIM,
            ).run()

        assertThat(resultPaper.trades).hasSize(1)
        assertThat(resultSim.trades).hasSize(1)
        // Paper fills at tracker mid; sim fills at ask. Different prices.
        val paperPrice =
            resultPaper.trades
                .single()
                .trade.price
        val simPrice =
            resultSim.trades
                .single()
                .trade.price
        assertThat(paperPrice.compareTo(simPrice))
            .withFailMessage("expected paper=$paperPrice and sim=$simPrice to differ")
            .isNotZero()
    }

    @Test
    fun `PAPER default is unchanged when brokerKind is not specified`() {
        val resultDefault =
            Backtest(
                strategies = listOf("s1" to buyOnFifthTick()),
                ticks = ticksWithBidAsk(8),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val resultExplicitPaper =
            Backtest(
                strategies = listOf("s1" to buyOnFifthTick()),
                ticks = ticksWithBidAsk(8),
                candleWindow = TimeWindow.ONE_MINUTE,
                brokerKind = BrokerKind.PAPER,
            ).run()
        // Same number of fills + same price — proves the new param has zero impact on default.
        assertThat(resultDefault.trades.map { it.trade.price })
            .isEqualTo(resultExplicitPaper.trades.map { it.trade.price })
    }
}
