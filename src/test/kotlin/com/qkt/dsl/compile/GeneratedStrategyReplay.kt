package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.backtest.TradeRecord
import com.qkt.candles.TimeWindow
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSourceCapability
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
    ) {
        val candles =
            (closes + closes.last()).mapIndexed { index, close -> candle(close, index) }
        assertTickAndBarParity(
            path,
            mapOf(SYMBOL to candles),
            window = TimeWindow.ONE_MINUTE,
            closeOnlyTicks = true,
        )
    }

    fun assertTickAndBarParity(
        path: Path,
        candlesBySymbol: Map<String, List<Candle>>,
        window: TimeWindow,
        closeOnlyTicks: Boolean = false,
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
                ticks = ticks,
                candleWindow = window,
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
                    source = barSource,
                    request =
                        MarketRequest(
                            symbols = symbols,
                            from = Instant.ofEpochMilli(allCandles.minOf { it.startTime }),
                            to = Instant.ofEpochMilli(allCandles.maxOf { it.endTime }),
                        ),
                    candleWindow = window,
                ).run()

        assertThat(tickResult.rejections).isEmpty()
        assertThat(barResult.rejections).isEmpty()
        assertThat(tickResult.trades).hasSize(1)
        assertThat(barResult.trades).hasSize(1)
        assertThat(canonical(barResult.trades.single()))
            .isEqualTo(canonical(tickResult.trades.single()))
    }

    private fun canonical(record: TradeRecord): List<Any> =
        with(record.trade) {
            listOf(record.strategyId, symbol, side, quantity, price, timestamp)
        }

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
