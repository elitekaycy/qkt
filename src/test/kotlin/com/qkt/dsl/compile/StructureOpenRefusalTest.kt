package com.qkt.dsl.compile

import com.qkt.common.SequentialIdGenerator
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.OpenStructure
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A structure sizes by contracts or PCT RISK; an OPEN on an undeclared root is suppressed with the reason. */
class StructureOpenRefusalTest {
    private val source =
        "STRATEGY s VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\nRULES\n    WHEN b.close > 0 THEN " +
            "OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { BUY CALL DELTA 0.3 DTE 7 TO 14 } SIZING 1\n"

    private val open =
        (
            (
                Dsl.parse(
                    source,
                ) as ParseResult.Success
            ).value.rules.single() as WhenThen
        ).action as OpenStructure

    private val compiler = StructureCompiler(ExprCompiler(), SequentialIdGenerator(prefix = "st-"))

    @Test
    fun `a notional size is not a structure size`() {
        assertThatThrownBy { compiler.compile(open.copy(sizing = SizeNotional(NumLit(BigDecimal.TEN)))) }
            .isInstanceOf(CompileError::class.java)
            .hasMessageContaining("structure ps sizes by contracts per leg or N PCT RISK")
    }

    @Test
    fun `an OPEN without a declared option root is suppressed`() {
        val one = BigDecimal.ONE
        val candle = Candle("BACKTEST:BTCUSDT", one, one, one, one, BigDecimal.ZERO, 0L, 1L)
        val ctx = EvalContext(candle, emptyMap(), emptyMap(), testStrategyContext())

        val signal = compiler.compile(open)(ctx).single() as Signal.Suppressed

        assertThat(signal.reason).isEqualTo("${open.root} is not a declared option root")
    }
}
