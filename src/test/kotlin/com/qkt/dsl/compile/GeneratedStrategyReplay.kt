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
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat

internal object GeneratedStrategyReplay {
    private const val SYMBOL = "BACKTEST:X"
    private val window = TimeWindow.ONE_MINUTE

    fun compile(path: Path): Strategy {
        val parsed = Dsl.parseFile(path)
        assertThat(parsed).isInstanceOf(ParseResult.Success::class.java)
        return AstCompiler().compile((parsed as ParseResult.Success).value)
    }

    fun assertTickAndBarParity(
        path: Path,
        closes: List<String>,
    ) {
        val candles =
            (closes + closes.last()).mapIndexed { index, close -> candle(close, index) }
        val ticks = candles.dropLast(1).map { candle -> Tick(SYMBOL, candle.close, candle.startTime) }
        val terminal = candles.last()

        val tickResult =
            Backtest(
                strategies = listOf("generated" to compile(path)),
                ticks = ticks + Tick(SYMBOL, terminal.close, terminal.startTime),
                candleWindow = window,
            ).run()

        val barSource = InMemoryMarketSource()
        barSource.seedBars(SYMBOL, window, candles)
        val barResult =
            Backtest
                .fromSource(
                    strategies = listOf("generated" to compile(path)),
                    source = barSource,
                    request =
                        MarketRequest(
                            symbols = listOf(SYMBOL),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(candles.last().endTime),
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
