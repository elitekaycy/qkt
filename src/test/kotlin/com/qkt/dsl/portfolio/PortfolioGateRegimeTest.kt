package com.qkt.dsl.portfolio

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioGateRegimeTest {
    private val clock = FixedClock(time = 0L)
    private val calendar = TradingCalendar.crypto()

    private fun candle(
        close: String,
        ts: Long = 0L,
    ) = Candle(
        symbol = "BACKTEST:BTCUSDT",
        open = Money.of(close),
        high = Money.of(close),
        low = Money.of(close),
        close = Money.of(close),
        volume = Money.of("1"),
        startTime = ts,
        endTime = ts + 60_000L,
    )

    private fun buildGate(src: String): PortfolioGate {
        val tokens = Lexer(src).tokenize()
        val result = Parser(tokens).parseFile()
        require(result is ParseResult.Success<*>) {
            "parse failed: ${(result as ParseResult.Failure).errors.joinToString { it.message }}"
        }
        val ast = (result.value as ParsedFile.PortfolioFile).ast
        return PortfolioGate(ast, clock, calendar).also {
            it.prepare()
            it.initialState()
        }
    }

    @Test
    fun `default regime selected when no conditional state matches`() {
        val gate =
            buildGate(
                """
                PORTFOLIO p VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                IMPORT 'a.qkt' AS a
                REGIMES
                    NAME r
                    STATE high WHEN btc.close > 200
                    STATE flat DEFAULT
                ALLOCATE
                    METHOD regime_weighted
                    high -> a 1.0
                    flat -> a 0.0
                RULES
                    RUN a
                """.trimIndent(),
            )
        gate.onCandle(candle("100"))
        assertThat(gate.currentState().regimeName).isEqualTo("flat")
        assertThat(gate.currentState().weightByAlias).containsEntry("a", BigDecimal("0.0"))
    }

    @Test
    fun `conditional regime selected after threshold`() {
        val gate =
            buildGate(
                """
                PORTFOLIO p VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                IMPORT 'a.qkt' AS a
                REGIMES
                    NAME r
                    STATE high WHEN btc.close > 200
                    STATE flat DEFAULT
                ALLOCATE
                    METHOD regime_weighted
                    high -> a 1.0
                    flat -> a 0.0
                RULES
                    RUN a
                """.trimIndent(),
            )
        gate.onCandle(candle("300"))
        assertThat(gate.currentState().regimeName).isEqualTo("high")
        assertThat(gate.currentState().weightByAlias).containsEntry("a", BigDecimal("1.0"))
    }

    @Test
    fun `weights exclude cash and missing aliases are zero`() {
        val gate =
            buildGate(
                """
                PORTFOLIO p VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                REGIMES
                    NAME r
                    STATE split DEFAULT
                ALLOCATE
                    METHOD regime_weighted
                    split -> a 0.5, cash 0.5
                RULES
                    RUN a
                    RUN b
                """.trimIndent(),
            )
        gate.onCandle(candle("100"))
        assertThat(gate.currentState().regimeName).isEqualTo("split")
        assertThat(gate.currentState().weightByAlias).containsEntry("a", BigDecimal("0.5"))
        assertThat(gate.currentState().weightByAlias).containsEntry("b", BigDecimal.ZERO)
    }

    @Test
    fun `no allocate block leaves weightByAlias empty`() {
        val gate =
            buildGate(
                """
                PORTFOLIO p VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1m
                IMPORT 'a.qkt' AS a
                REGIMES
                    NAME r
                    STATE high WHEN btc.close > 200
                    STATE flat DEFAULT
                RULES
                    RUN a
                """.trimIndent(),
            )
        gate.onCandle(candle("300"))
        assertThat(gate.currentState().regimeName).isNull()
        assertThat(gate.currentState().weightByAlias).isEmpty()
    }
}
