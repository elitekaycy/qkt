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
}
