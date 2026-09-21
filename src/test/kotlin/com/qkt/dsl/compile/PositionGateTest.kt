package com.qkt.dsl.compile

import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionGateTest {
    private fun gate(condition: String): PositionGate {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m,
                eth = BACKTEST:ETHUSDT EVERY 1m
            RULES
                WHEN $condition THEN BUY btc SIZING 1
            """.trimIndent()
        val ast = (Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success).value
        return PositionGate.of((ast.rules.single() as WhenThen).cond, ruleAlias = "btc")
    }

    @Test
    fun `a top-level flat term on the rule's own stream is a flat gate, in any position and either order`() {
        assertThat(gate("btc.close > 0 AND POSITION.btc = 0")).isEqualTo(PositionGate.FLAT)
        assertThat(gate("POSITION.btc = 0 AND btc.close > 0 AND OPEN_ORDERS.btc = 0")).isEqualTo(PositionGate.FLAT)
        assertThat(gate("btc.close > 0 AND 0 = POSITION.btc")).isEqualTo(PositionGate.FLAT)
    }

    @Test
    fun `a top-level held term is a held gate, whichever way it is written`() {
        assertThat(gate("POSITION.btc != 0")).isEqualTo(PositionGate.HELD)
        assertThat(gate("POSITION.btc > 0 AND btc.close < 100")).isEqualTo(PositionGate.HELD)
        assertThat(gate("btc.close < 100 AND POSITION.btc < 0")).isEqualTo(PositionGate.HELD)
    }

    @Test
    fun `anything whose truth is not fixed by the position state alone is not a gate`() {
        assertThat(gate("btc.close > 0")).isEqualTo(PositionGate.NONE)
        assertThat(gate("btc.close > 0 OR POSITION.btc = 0")).isEqualTo(PositionGate.NONE)
        assertThat(gate("btc.close > 0 AND POSITION.eth = 0")).isEqualTo(PositionGate.NONE)
        assertThat(gate("btc.close > 0 AND POSITION.btc >= 0")).isEqualTo(PositionGate.NONE)
        assertThat(gate("btc.close > 0 AND NOT (POSITION.btc = 0)")).isEqualTo(PositionGate.NONE)
    }
}
