package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A LOG-only rule that reads a completed SEQUENCE consumes the completion, so the sequence restarts. */
class SequenceLogConsumerTest {
    private val strategy =
        AstCompiler().compile(
            (
                Dsl.parse(
                    """
                    STRATEGY s VERSION 1
                    SYMBOLS
                        g = BACKTEST:XAUUSD EVERY 1m
                    SEQUENCE up ON g {
                        STAGE first: g.close > 100
                        STAGE second: g.close > 105
                    }
                    RULES
                        WHEN SEQUENCE.up.complete THEN LOG "sequence done"
                        WHEN SEQUENCE.up.stage = 1 THEN BUY g SIZING 1
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value,
        )

    @Test
    fun `after the logged completion the first stage arms again`() {
        val buys =
            listOf("101", "106", "101").mapIndexed { i, close ->
                val c = BigDecimal(close)
                val candle = Candle("BACKTEST:XAUUSD", c, c, c, c, BigDecimal.ONE, i * 60_000L, (i + 1) * 60_000L - 1)
                val out = mutableListOf<Signal>()
                strategy.onCandle(candle, testStrategyContext()) { out += it }
                out.size
            }

        assertThat(buys).containsExactly(1, 0, 1)
    }
}
