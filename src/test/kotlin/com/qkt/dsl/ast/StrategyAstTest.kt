package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StrategyAstTest {
    @Test
    fun `StrategyAst captures name version streams lets defaults rules`() {
        val ast =
            StrategyAst(
                name = "ema_x",
                version = 1,
                streams = listOf(StreamDecl("btc", "BACKTEST", "BTCUSDT", "1m")),
                constants = emptyList(),
                lets =
                    listOf(
                        LetDecl(
                            "fast",
                            IndicatorCall(
                                "EMA",
                                listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("9"))),
                            ),
                        ),
                    ),
                defaults = null,
                rules = emptyList(),
            )
        assertThat(ast.name).isEqualTo("ema_x")
        assertThat(ast.version).isEqualTo(1)
        assertThat(ast.streams).hasSize(1)
        assertThat(ast.lets).hasSize(1)
    }

    @Test
    fun `StrategyAst rejects empty name`() {
        assertThatThrownBy {
            StrategyAst(
                name = "",
                version = 1,
                streams = emptyList(),
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `StrategyAst rejects negative version`() {
        assertThatThrownBy {
            StrategyAst(
                name = "x",
                version = -1,
                streams = emptyList(),
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `StreamDecl rejects empty alias`() {
        assertThatThrownBy {
            StreamDecl(alias = "", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
