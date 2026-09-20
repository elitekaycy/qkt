package com.qkt.dsl.compile

import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Position-state accessors compiled by [StateAccessorCompiler]. */
class StateAccessorCompilerTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    @Test
    fun `POSITION count longs shorts gross read the truthful leg view`() {
        // A filled long and a filled short net to a misleading 0.1, but there are really TWO
        // open positions. The truthful accessors expose count/longs/shorts/gross.
        val pos =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions() = emptyMap<String, Position>()

                override fun openCountFor(symbol: String) = if (symbol == "BACKTEST:BTCUSDT") 2 else 0

                override fun longCountFor(symbol: String) = if (symbol == "BACKTEST:BTCUSDT") 1 else 0

                override fun shortCountFor(symbol: String) = if (symbol == "BACKTEST:BTCUSDT") 1 else 0

                override fun grossFor(symbol: String) =
                    if (symbol == "BACKTEST:BTCUSDT") BigDecimal("0.3") else BigDecimal.ZERO
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )

        fun read(src: StateSource) = (ExprCompiler().compile(StateAccessor(src, "btc")).evaluate(ec) as Value.Num).v
        assertThat(read(StateSource.POSITION_OPEN_COUNT)).isEqualByComparingTo("2")
        assertThat(read(StateSource.POSITION_LONG_COUNT)).isEqualByComparingTo("1")
        assertThat(read(StateSource.POSITION_SHORT_COUNT)).isEqualByComparingTo("1")
        assertThat(read(StateSource.POSITION_GROSS)).isEqualByComparingTo("0.3")
    }

    @Test
    fun `POSITION_AVG_PRICE reads avg entry price`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    com.qkt.positions.Position("BACKTEST:BTCUSDT", BigDecimal("1"), BigDecimal("105.50"))

                override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val v =
            ExprCompiler()
                .compile(
                    com.qkt.dsl.ast
                        .StateAccessor(com.qkt.dsl.ast.StateSource.POSITION_AVG_PRICE, "btc"),
                ).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("105.50")
    }

    @Test
    fun `POSITION_HOLDING_DURATION is seconds since the position opened`() {
        val pos =
            object : StrategyPositionView {
                override fun positionFor(symbol: String) =
                    Position("BACKTEST:BTCUSDT", BigDecimal("1"), BigDecimal("100"), openedAt = 0L)

                override fun allPositions() = emptyMap<String, Position>()
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext =
                    testStrategyContext(
                        clock = com.qkt.common.FixedClock(time = 7_200_000L),
                        positions = pos,
                    ),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_HOLDING_DURATION, "btc"))
                .evaluate(ec) as Value.Num
        // Open for two hours of clock time -> 7200 seconds, not 7,200,000 ms.
        assertThat(v.v).isEqualByComparingTo("7200")
    }

    @Test
    fun `POSITION_HOLDING_DURATION is zero when flat`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_HOLDING_DURATION, "btc"))
                .evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("0")
    }

    @Test
    fun `POSITION_MFE reads from positions view mfeFor`() {
        val pos =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions(): Map<String, Position> = emptyMap()

                override fun mfeFor(symbol: String): BigDecimal =
                    if (symbol == "BACKTEST:BTCUSDT") BigDecimal("12.34") else BigDecimal.ZERO
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_MFE, "btc"))
                .evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("12.34")
    }

    @Test
    fun `POSITION_MFE returns zero when view has no mfe data`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_MFE, "btc"))
                .evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("0")
    }

    @Test
    fun `POSITION_MAE reads from positions view maeFor`() {
        val pos =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions(): Map<String, Position> = emptyMap()

                override fun maeFor(symbol: String): BigDecimal =
                    if (symbol == "BACKTEST:BTCUSDT") BigDecimal("7.50") else BigDecimal.ZERO
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_MAE, "btc"))
                .evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7.50")
    }

    @Test
    fun `POSITION_MAE returns zero when view has no mae data`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v =
            ExprCompiler()
                .compile(StateAccessor(StateSource.POSITION_MAE, "btc"))
                .evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("0")
    }
}
