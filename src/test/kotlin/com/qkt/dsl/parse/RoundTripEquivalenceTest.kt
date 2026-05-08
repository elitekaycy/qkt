package com.qkt.dsl.parse

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.kotlin.SYMBOL
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.childRr
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.positionFull
import com.qkt.dsl.kotlin.strategy
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RoundTripEquivalenceTest {
    private fun parsedAst(name: String) =
        (Dsl.parseFile(Path.of("src/test/resources/dsl/$name")) as ParseResult.Success).value

    @Test
    fun `single stream market buy round-trips`() {
        val parsed = parsedAst("single_stream_market_buy.qkt")
        val handwritten =
            strategy("single_stream", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                rule {
                    whenever(btc.close gt 100.bd)
                    then { buy(stream = btc, qty = BigDecimal.ONE.bd) }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `multi-timeframe round-trips`() {
        val parsed = parsedAst("multi_timeframe.qkt")
        val handwritten =
            strategy("mtf", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                val btcH1 = stream("btc_h1", "BACKTEST", "BTCUSDT", "1h")
                rule {
                    whenever((btc.close gt 105.bd) and (btcH1.close gt 100.bd))
                    then { buy(stream = btc, qty = BigDecimal.ONE.bd) }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `multi-broker round-trips`() {
        val parsed = parsedAst("multi_broker.qkt")
        val handwritten =
            strategy("mb", 1) {
                val btc = stream("btc", "BYBIT", "BTCUSDT", "1m")
                val gold = stream("gold", "INTERACTIVE", "XAUUSD", "1m")
                val aapl = stream("aapl", "ALPACA", "AAPL", "1m")
                rule {
                    whenever(btc.close gt 0.bd)
                    then { buy(stream = btc, qty = BigDecimal.ONE.bd) }
                }
                rule {
                    whenever(gold.close gt 0.bd)
                    then { buy(stream = gold, qty = BigDecimal.ONE.bd) }
                }
                rule {
                    whenever(aapl.close gt 0.bd)
                    then { buy(stream = aapl, qty = BigDecimal.ONE.bd) }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `for each round-trips with iter-var substituted`() {
        val parsed = parsedAst("for_each.qkt")
        val handwritten =
            strategy("fe", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                val gold = stream("gold", "BACKTEST", "XAUUSD", "1m")
                val aapl = stream("aapl", "BACKTEST", "AAPL", "1m")
                forEach(btc, gold, aapl) { s ->
                    rule {
                        whenever(s.close gt 0.bd)
                        then { buy(stream = s, qty = BigDecimal.ONE.bd) }
                    }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `defaults with SYMBOL placeholder round-trips`() {
        val parsed = parsedAst("defaults_with_symbol.qkt")
        val handwritten =
            strategy("ds", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                defaults {
                    sizing =
                        com.qkt.dsl.ast
                            .SizeQty(NumLit(BigDecimal.ONE))
                    stopLoss =
                        childBy(
                            com.qkt.dsl.ast.BinaryOp(
                                com.qkt.dsl.ast.BinOp.MUL,
                                IndicatorCall("ATR", listOf(SYMBOL, NumLit(BigDecimal("14")))),
                                NumLit(BigDecimal("2")),
                            ),
                        )
                    takeProfit = childRr(BigDecimal("3").bd)
                    tif = com.qkt.dsl.ast.Gtc
                }
                rule {
                    whenever(btc.close gt 100.bd)
                    then { buy(stream = btc) }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `momentum basket round-trips`() {
        val parsed = parsedAst("momentum_basket.qkt")
        val handwritten =
            strategy("momentum_basket", 1) {
                val btc = stream("btc", "BYBIT", "BTCUSDT", "1m")
                val btcH1 = stream("btc_h1", "BYBIT", "BTCUSDT", "1h")
                val gold = stream("gold", "INTERACTIVE", "XAUUSD", "15m")
                val aapl = stream("aapl", "ALPACA", "AAPL", "5m")
                defaults {
                    sizing =
                        com.qkt.dsl.ast
                            .SizeQty(NumLit(BigDecimal.ONE))
                    stopLoss = childBy(BigDecimal("5").bd)
                    takeProfit = childRr(BigDecimal("3").bd)
                    tif = com.qkt.dsl.ast.Gtc
                }
                forEach(btc, gold, aapl) { s ->
                    rule {
                        whenever(s.close gt 100.bd)
                        then { buy(stream = s) }
                    }
                }
                rule {
                    whenever((position(btc) gt 0.bd) and (btcH1.close lt 100.bd))
                    then { sell(stream = btc, sizing = positionFull(btc)) }
                }
                rule {
                    whenever(
                        com.qkt.dsl.ast
                            .AccountRef("equity") lt BigDecimal("9500").bd,
                    )
                    then { closeAll() }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }
}
