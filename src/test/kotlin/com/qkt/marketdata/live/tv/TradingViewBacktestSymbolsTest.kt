package com.qkt.marketdata.live.tv

import com.qkt.cli.MarketSourceFactory
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.SymbolPattern
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingViewBacktestSymbolsTest {
    @Test
    fun `a BACKTEST stream is refused with the TradingView symbol to write instead`() {
        val message = TradingViewBacktestSymbols.refusal(listOf("BACKTEST:EURUSD", "OANDA:GBPUSD"))

        assertThat(message)
            .contains("BACKTEST:EURUSD -> OANDA:EURUSD")
            .contains("source: tv")
            .contains("EXNESS:EURUSD")
            .doesNotContain("GBPUSD")
            .doesNotContain("\n")
    }

    @Test
    fun `the replacement names the exchange TradingView lists the pair on`() {
        assertThat(TradingViewBacktestSymbols.liveSymbolFor("XAUUSD")).isEqualTo("OANDA:XAUUSD")
        assertThat(TradingViewBacktestSymbols.liveSymbolFor("USDJPY")).isEqualTo("OANDA:USDJPY")
        assertThat(TradingViewBacktestSymbols.liveSymbolFor("BTCUSD")).isEqualTo("COINBASE:BTCUSD")
        assertThat(TradingViewBacktestSymbols.liveSymbolFor("ETHUSDT")).isEqualTo("BINANCE:ETHUSDT")
        assertThat(TradingViewBacktestSymbols.liveSymbolFor("US500")).isNull()
        assertThat(TradingViewBacktestSymbols.refusal(listOf("BACKTEST:US500")))
            .contains("BACKTEST:US500 -> TradingView's own EXCHANGE:US500")
    }

    @Test
    fun `streams with live prefixes pass`() {
        assertThat(TradingViewBacktestSymbols.refusal(listOf("OANDA:EURUSD", "EXNESS:XAUUSD"))).isNull()
    }

    @Test
    fun `the paper feed refuses an unrouted BACKTEST stream when the run or deploy asks for it`() {
        val factory = MarketSourceFactory.composite(emptyList(), source = "tv") { NullMarketSource }

        assertThatThrownBy { factory(listOf("BACKTEST:EURUSD")) }
            .isInstanceOf(UnservableStreamException::class.java)
            .hasMessageContaining("BACKTEST:EURUSD -> OANDA:EURUSD")
        assertThatCode { factory(listOf("OANDA:EURUSD")) }.doesNotThrowAnyException()
    }

    @Test
    fun `a BACKTEST stream is left alone when a route serves it or the feed is not TradingView`() {
        val routed =
            MarketSourceFactory.composite(
                listOf(SymbolPattern.prefix("BACKTEST:") to NullMarketSource),
                source = "tv",
            ) { NullMarketSource }
        assertThatCode { routed(listOf("BACKTEST:EURUSD")) }.doesNotThrowAnyException()

        val replay = MarketSourceFactory.composite(emptyList(), source = "replay") { NullMarketSource }
        assertThatCode { replay(listOf("BACKTEST:EURUSD")) }.doesNotThrowAnyException()
    }
}
