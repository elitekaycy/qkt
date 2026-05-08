package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.kotlin.SYMBOL
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.strategy
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SymbolPlaceholderTest {
    @Test
    fun `SYMBOL inside defaults stop loss substitutes per rule alias`() {
        val ast =
            strategy("sp", 1) {
                val btc = stream("btc", "BYBIT", "BTCUSDT", "1m")
                val gold = stream("gold", "INTERACTIVE", "XAUUSD", "1m")
                defaults {
                    stopLoss =
                        childBy(
                            IndicatorCall(
                                "atr",
                                listOf(
                                    SYMBOL,
                                    com.qkt.dsl.ast
                                        .NumLit(BigDecimal("14")),
                                ),
                            ),
                        )
                    takeProfit =
                        com.qkt.dsl.kotlin
                            .childRr(BigDecimal("3").bd)
                }
                rule {
                    whenever(btc.close gt 100.bd)
                    then { buy(stream = btc, qty = BigDecimal.ONE.bd) }
                }
                rule {
                    whenever(gold.close gt 1000.bd)
                    then { buy(stream = gold, qty = BigDecimal.ONE.bd) }
                }
            }

        val merged0 = mergeDefaults((ast.rules[0] as WhenThen).action, ast.defaults) as Buy
        val sl0 = merged0.opts.bracket?.stopLoss as ChildBy
        val ind0 = sl0.distance as IndicatorCall
        val series0 = ind0.args.first() as StreamFieldRef
        assertThat(series0.stream).isEqualTo("btc")
        assertThat(series0.field).isEqualTo("candle")

        val merged1 = mergeDefaults((ast.rules[1] as WhenThen).action, ast.defaults) as Buy
        val sl1 = merged1.opts.bracket?.stopLoss as ChildBy
        val ind1 = sl1.distance as IndicatorCall
        val series1 = ind1.args.first() as StreamFieldRef
        assertThat(series1.stream).isEqualTo("gold")
    }

    @Test
    fun `SYMBOL outside defaults is rejected at compile`() {
        val ast =
            strategy("sp_bad", 1) {
                val btc = stream("btc", "BYBIT", "BTCUSDT", "1m")
                rule {
                    whenever(SYMBOL gt 100.bd)
                    then { buy(stream = btc, qty = BigDecimal.ONE.bd) }
                }
            }
        assertThatThrownBy { AstCompiler().compile(ast) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
