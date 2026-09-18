package com.qkt.dsl.parse

import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.duration
import com.qkt.dsl.kotlin.entryPrice
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.layer
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.plus
import com.qkt.dsl.kotlin.stack
import com.qkt.dsl.kotlin.stackOf
import com.qkt.dsl.kotlin.strategy
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RoundTripActionEquivalenceTest {
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
    fun `LOG with level placeholder field round trips`() {
        val parsed =
            (
                Dsl.parse(
                    """
                    STRATEGY t VERSION 1
                    SYMBOLS
                        btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                        WHEN btc.close > 100
                        THEN LOG WARN 'buy at {price}' price=btc.close
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value
        val handwritten =
            strategy("t", 1) {
                val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
                rule {
                    whenever(btc.close gt 100.bd)
                    then { warn("buy at {price}", "price" to btc.close) }
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
