package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyBuilderTest {
    @Test
    fun `empty strategy header round-trips`() {
        val ast: StrategyAst = strategy("ema_x", version = 1) {}
        assertThat(ast.name).isEqualTo("ema_x")
        assertThat(ast.version).isEqualTo(1)
        assertThat(ast.streams).isEmpty()
        assertThat(ast.rules).isEmpty()
    }

    @Test
    fun `stream registers a StreamDecl and returns a handle`() {
        val ast =
            strategy("s", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                assertThat(btc.alias).isEqualTo("btc")
            }
        assertThat(ast.streams).containsExactly(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m"))
    }

    @Test
    fun `ema helper builds an IndicatorCall`() {
        val btc = StreamRef("btc")
        val expr = ema(btc.close, period = 9)
        assertThat(expr).isInstanceOf(com.qkt.dsl.ast.IndicatorCall::class.java)
        val ic = expr as com.qkt.dsl.ast.IndicatorCall
        assertThat(ic.name).isEqualTo("EMA")
        assertThat(ic.args).hasSize(2)
    }

    @Test
    fun `comparison and arithmetic operators build AST nodes`() {
        val btc = StreamRef("btc")
        val cond = btc.close gt 100.bd
        assertThat(cond).isInstanceOf(com.qkt.dsl.ast.CmpOp::class.java)
        val sum = btc.high - btc.low
        assertThat(sum).isInstanceOf(com.qkt.dsl.ast.BinaryOp::class.java)
    }

    @Test
    fun `LET delegate registers a LetDecl and produces a Ref`() {
        val ast =
            strategy("s", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                val fast by letting(ema(btc.close, period = 9))
                assertThat(fast).isInstanceOf(com.qkt.dsl.ast.Ref::class.java)
            }
        assertThat(ast.lets).hasSize(1)
        assertThat(ast.lets[0].name).isEqualTo("fast")
    }

    @Test
    fun `rule block builds a WhenThen with Buy action`() {
        val ast =
            strategy("s", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                rule {
                    whenever(btc.close gt 100.bd)
                    then { buy(btc, qty = 1.bd) }
                }
            }
        assertThat(ast.rules).hasSize(1)
        val r = ast.rules[0] as com.qkt.dsl.ast.WhenThen
        assertThat(r.action).isInstanceOf(com.qkt.dsl.ast.Buy::class.java)
    }
}
