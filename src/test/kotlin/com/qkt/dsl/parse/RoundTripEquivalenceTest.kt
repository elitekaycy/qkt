package com.qkt.dsl.parse

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.kotlin.SYMBOL
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.childRr
import com.qkt.dsl.kotlin.duration
import com.qkt.dsl.kotlin.entryPrice
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.layer
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.plus
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.positionFull
import com.qkt.dsl.kotlin.stack
import com.qkt.dsl.kotlin.stackOf
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

    @Test
    fun `STACK SPACING round trips`() {
        val parsed =
            (
                Dsl.parse(
                    """
                    STRATEGY t VERSION 1
                    SYMBOLS
                        btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                        WHEN btc.close > 100
                        THEN BUY btc SIZING 0.1 STACK 3 SPACING 100 ABOVE WITHIN 1h
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val handwritten =
            strategy("t", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                rule {
                    whenever(btc.close gt 100.bd)
                    then {
                        buy(
                            stream = btc,
                            qty = "0.1".bd,
                            stack =
                                stack(
                                    count = 3,
                                    spacing = NumLit(BigDecimal("100")),
                                    direction = StackDirection.ABOVE,
                                    within = duration("1h"),
                                ),
                        )
                    }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `CANCEL round trips`() {
        val parsed =
            (
                Dsl.parse(
                    """
                    STRATEGY t VERSION 1
                    SYMBOLS
                        btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                        WHEN btc.close > 100
                        THEN CANCEL btc
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val handwritten =
            strategy("t", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                rule {
                    whenever(btc.close gt 100.bd)
                    then { cancelStream(btc) }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `CANCEL_ALL round trips`() {
        val parsed =
            (
                Dsl.parse(
                    """
                    STRATEGY t VERSION 1
                    SYMBOLS
                        btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                        WHEN btc.close < 50
                        THEN CANCEL_ALL
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val handwritten =
            strategy("t", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                rule {
                    whenever(btc.close lt 50.bd)
                    then { cancelAll() }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `STACK layer-list round trips`() {
        val parsed =
            (
                Dsl.parse(
                    """
                    STRATEGY t VERSION 1
                    SYMBOLS
                        btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                        WHEN btc.close > 100
                        THEN BUY btc STACK [ 0.1, 0.2 AT entry + 100, 0.3 LIMIT AT entry + 200 ]
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val limitExpr = entryPrice + NumLit(BigDecimal("200"))
        val handwritten =
            strategy("t", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                rule {
                    whenever(btc.close gt 100.bd)
                    then {
                        buy(
                            stream = btc,
                            stack =
                                stackOf(
                                    layer(qty = NumLit(BigDecimal("0.1"))),
                                    layer(qty = NumLit(BigDecimal("0.2")), at = entryPrice + NumLit(BigDecimal("100"))),
                                    layer(
                                        qty = NumLit(BigDecimal("0.3")),
                                        orderType = Limit(limitExpr),
                                        at = limitExpr,
                                    ),
                                ),
                        )
                    }
                }
            }
        assertThat(parsed).isEqualTo(handwritten)
    }
}
