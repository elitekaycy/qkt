package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * A bracket whose stop resolves onto the entry price has no stop distance: risk sizing cannot divide
 * by it and a reward-to-risk target would sit on the entry. The order is refused with a reason, never
 * sent and never thrown out of the rule fire.
 */
class BracketStopAtEntryEndToEndTest {
    private fun compile(source: String): DslCompiledStrategy =
        when (val parsed = Dsl.parse(source)) {
            is ParseResult.Success -> AstCompiler().compile(parsed.value) as DslCompiledStrategy
            is ParseResult.Failure ->
                error(parsed.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" })
        }

    private fun candle(close: String): Candle {
        val p = BigDecimal(close)
        return Candle("BACKTEST:BTCUSDT", p, p, p, p, BigDecimal.ZERO, 0L, 60_000L)
    }

    private fun fundedContext() =
        testStrategyContext(
            pnl =
                object : StrategyPnLView {
                    override fun realized(): BigDecimal = BigDecimal.ZERO

                    override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                    override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                    override fun total(): BigDecimal = BigDecimal.ZERO

                    override fun equity(): BigDecimal = BigDecimal("10000")

                    override fun balance(): BigDecimal = BigDecimal("10000")
                },
        )

    @Test
    fun `a stop on the entry price refuses the bracket with a reason and leaves other actions alone`() {
        val strategy =
            compile(
                """
                STRATEGY stop_at_entry VERSION 1
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 0
                  THEN BUY btc SIZING 1 PCT RISK BRACKET {
                    STOP LOSS AT btc.close,
                    TAKE PROFIT RR 2
                  } ; SELL btc SIZING 1
                """.trimIndent(),
            )
        val captured = mutableListOf<Signal>()

        assertThatCode { strategy.onCandle(candle("100"), fundedContext(), captured::add) }.doesNotThrowAnyException()

        assertThat(captured).hasSize(2)
        val refused = captured.filterIsInstance<Signal.Suppressed>().single()
        assertThat(refused.symbol).isEqualTo("BACKTEST:BTCUSDT")
        assertThat(refused.reason).contains("stop distance").contains("100")
        assertThat(captured.filterIsInstance<Signal.Submit>()).isEmpty()
        assertThat(captured.last()).isInstanceOf(Signal.Sell::class.java)
    }
}
