package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.backtest.TradeRecord
import com.qkt.candles.TimeWindow
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltRule
import com.qkt.risk.StrategyRiskLimits
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat

internal object GeneratedStrategyReplay {
    private const val SYMBOL = "BACKTEST:X"

    fun compile(path: Path): Strategy = namedStrategy(path).second

    private fun namedStrategy(path: Path): Pair<String, Strategy> {
        val parsed = Dsl.parseFile(path)
        assertThat(parsed).isInstanceOf(ParseResult.Success::class.java)
        val ast = (parsed as ParseResult.Success).value
        return ast.name to AstCompiler().compile(ast)
    }

    fun assertTickAndBarParity(
        path: Path,
        closes: List<String>,
        expectedTradeCount: Int = 1,
        expectedRejectionCount: Int = 0,
        expectedHaltCount: Int = 0,
        startingBalance: BigDecimal = BigDecimal.ZERO,
        bookCapital: BigDecimal? = null,
        instruments: InstrumentRegistry = NoopInstrumentRegistry,
        strategyRiskLimits: StrategyRiskLimits = StrategyRiskLimits(),
        maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
        maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
        dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
        totalDdBasis: DrawdownBasis = DrawdownBasis.STATIC,
        haltRules: () -> List<HaltRule> = { emptyList() },
    ) {
        val candles =
            (closes + closes.last()).mapIndexed { index, close -> candle(close, index) }
        assertTickAndBarParity(
            path,
            mapOf(SYMBOL to candles),
            window = TimeWindow.ONE_MINUTE,
            closeOnlyTicks = true,
            expectedTradeCount = expectedTradeCount,
            expectedRejectionCount = expectedRejectionCount,
            expectedHaltCount = expectedHaltCount,
            startingBalance = startingBalance,
            bookCapital = bookCapital,
            instruments = instruments,
            strategyRiskLimits = strategyRiskLimits,
            maxOrderQty = maxOrderQty,
            maxOrderNotional = maxOrderNotional,
            dailyDdBasis = dailyDdBasis,
            totalDdBasis = totalDdBasis,
            haltRules = haltRules,
        )
    }

    fun assertTickAndBarParity(
        path: Path,
        candlesBySymbol: Map<String, List<Candle>>,
        window: TimeWindow,
        closeOnlyTicks: Boolean = false,
        expectedTradeCount: Int = 1,
        expectedRejectionCount: Int = 0,
        expectedHaltCount: Int = 0,
        startingBalance: BigDecimal = BigDecimal.ZERO,
        bookCapital: BigDecimal? = null,
        instruments: InstrumentRegistry = NoopInstrumentRegistry,
        strategyRiskLimits: StrategyRiskLimits = StrategyRiskLimits(),
        maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
        maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
        dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
        totalDdBasis: DrawdownBasis = DrawdownBasis.STATIC,
        haltRules: () -> List<HaltRule> = { emptyList() },
    ) {
        val symbols = candlesBySymbol.keys.toList()
        val allCandles = candlesBySymbol.values.flatten()
        val ticks =
            allCandles
                .flatMap { candle -> if (closeOnlyTicks) closeOnlyTicks(candle) else ohlcvTicks(candle) }
                .sortedWith(compareBy<Tick> { it.timestamp }.thenBy { symbols.indexOf(it.symbol) })

        val tickResult =
            Backtest(
                strategies = listOf(namedStrategy(path)),
                haltRules = haltRules(),
                ticks = ticks,
                candleWindow = window,
                startingBalance = startingBalance,
                startingBalances = mapOf(namedStrategy(path).first to startingBalance),
                bookCapital = bookCapital,
                instruments = instruments,
                strategyRiskLimits = mapOf(namedStrategy(path).first to strategyRiskLimits),
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                dailyDdBasis = dailyDdBasis,
                totalDdBasis = totalDdBasis,
            ).run()

        val barSource =
            object : InMemoryMarketSource() {
                override val capabilities: Set<MarketSourceCapability> =
                    super.capabilities + MarketSourceCapability.VOLUME
            }
        candlesBySymbol.forEach { (symbol, candles) -> barSource.seedBars(symbol, window, candles) }
        val barResult =
            Backtest
                .fromSource(
                    strategies = listOf(namedStrategy(path)),
                    haltRules = haltRules(),
                    source = barSource,
                    request =
                        MarketRequest(
                            symbols = symbols,
                            from = Instant.ofEpochMilli(allCandles.minOf { it.startTime }),
                            to = Instant.ofEpochMilli(allCandles.maxOf { it.endTime }),
                        ),
                    candleWindow = window,
                    startingBalance = startingBalance,
                    startingBalances = mapOf(namedStrategy(path).first to startingBalance),
                    bookCapital = bookCapital,
                    instruments = instruments,
                    strategyRiskLimits = mapOf(namedStrategy(path).first to strategyRiskLimits),
                    maxOrderQty = maxOrderQty,
                    maxOrderNotional = maxOrderNotional,
                    dailyDdBasis = dailyDdBasis,
                    totalDdBasis = totalDdBasis,
                ).run()

        assertThat(tickResult.rejections).hasSize(expectedRejectionCount)
        assertThat(barResult.rejections).hasSize(expectedRejectionCount)
        assertThat(barResult.rejections.map(::canonical))
            .isEqualTo(tickResult.rejections.map(::canonical))
        assertThat(tickResult.halts).hasSize(expectedHaltCount)
        assertThat(barResult.halts).hasSize(expectedHaltCount)
        assertThat(barResult.halts.map(::canonical))
            .isEqualTo(tickResult.halts.map(::canonical))
        assertThat(tickResult.trades).hasSize(expectedTradeCount)
        assertThat(barResult.trades).hasSize(expectedTradeCount)
        assertThat(barResult.trades.map(::canonical))
            .isEqualTo(tickResult.trades.map(::canonical))
    }

    private fun canonical(record: TradeRecord): List<Any> =
        with(record.trade) {
            listOf(record.strategyId, symbol, side, quantity, price, timestamp)
        }

    private fun canonical(event: RiskRejectedEvent): List<Any> =
        with(event.request) {
            listOf(strategyId, symbol, side, quantity, event.reason, event.timestamp)
        }

    private fun canonical(event: RiskEvent.Halted): List<Any?> =
        listOf(event.reason, event.strategyId, event.cancelWorkingOrders, event.timestamp)

    private fun closeOnlyTicks(candle: Candle): List<Tick> =
        listOf(Tick(candle.symbol, candle.close, candle.startTime, volume = candle.volume))

    private fun ohlcvTicks(candle: Candle): List<Tick> {
        val step = (candle.endTime - candle.startTime) / 4
        return listOf(
            Tick(candle.symbol, candle.open, candle.startTime),
            Tick(candle.symbol, candle.low, candle.startTime + step),
            Tick(candle.symbol, candle.high, candle.startTime + 2 * step),
            Tick(candle.symbol, candle.close, candle.endTime - 1, volume = candle.volume),
        )
    }

    private fun candle(
        close: String,
        index: Int,
    ): Candle =
        Candle(
            symbol = SYMBOL,
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = index * 60_000L,
            endTime = (index + 1) * 60_000L,
        )
}
