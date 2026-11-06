package com.qkt.dsl.compile

import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Per-stream `POSITION.<s>.*` reads are zero or undefined while flat and fail on an undeclared stream. */
class PositionAccessorFlatTest {
    private val one = BigDecimal.ONE
    private val ctx =
        EvalContext(
            Candle("BACKTEST:XAUUSD", one, one, one, one, BigDecimal.ZERO, 0L, 1L),
            mapOf("g" to HubKey("BACKTEST", "XAUUSD", "1m")),
            emptyMap(),
            testStrategyContext(),
            evaluationTimeMs = 60_000L,
        )

    private val numericSources =
        listOf(
            StateSource.POSITION_PNL,
            StateSource.POSITION_HOLDING_DURATION,
            StateSource.POSITION_MFE,
            StateSource.POSITION_MAE,
            StateSource.POSITION_OPEN_COUNT,
            StateSource.POSITION_LONG_COUNT,
            StateSource.POSITION_SHORT_COUNT,
            StateSource.POSITION_GROSS,
            StateSource.POSITION_TRADES_TODAY,
            StateSource.OPEN_ORDERS,
        )

    private fun read(
        source: StateSource,
        alias: String = "g",
    ): Value = StateAccessorCompiler.compile(StateAccessor(source, alias)).evaluate(ctx)

    @Test
    fun `numeric position reads are zero while flat`() {
        for (source in numericSources) {
            assertThat((read(source) as Value.Num).v).describedAs(source.name).isEqualByComparingTo("0")
        }
    }

    @Test
    fun `entry price and last trade time are undefined while flat`() {
        assertThat(read(StateSource.POSITION_AVG_PRICE)).isEqualTo(Value.Undefined)
        assertThat(read(StateSource.POSITION_LAST_TRADE_AT)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `every per-stream read fails on an undeclared stream`() {
        for (source in numericSources + StateSource.POSITION_AVG_PRICE + StateSource.POSITION_LAST_TRADE_AT) {
            assertThatThrownBy {
                read(source, alias = "zz")
            }.describedAs(source.name).hasMessageContaining("Unknown stream alias: zz")
        }
    }
}
