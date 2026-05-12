package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerStateTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private fun ctxWithPnl(
        realized: BigDecimal = BigDecimal.ZERO,
        unrealizedTotal: BigDecimal = BigDecimal.ZERO,
    ): EvalContext {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = realized

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = unrealizedTotal

                override fun total(): BigDecimal = realized.add(unrealizedTotal)

                override fun equity(): BigDecimal = realized.add(unrealizedTotal)

                override fun balance(): BigDecimal = realized
            }
        return EvalContext(
            candle = candle,
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(pnl = pnl),
        )
    }

    @Test
    fun `ACCOUNT realized_pnl reads from pnl view`() {
        val v =
            ExprCompiler()
                .compile(AccountRef("realized_pnl"))
                .evaluate(ctxWithPnl(realized = BigDecimal("123.45"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("123.45")
    }

    @Test
    fun `ACCOUNT unrealized_pnl reads from pnl view`() {
        val v =
            ExprCompiler()
                .compile(AccountRef("unrealized_pnl"))
                .evaluate(ctxWithPnl(unrealizedTotal = BigDecimal("7.5"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7.5")
    }

    @Test
    fun `ACCOUNT total_pnl reads from pnl view`() {
        val v =
            ExprCompiler()
                .compile(AccountRef("total_pnl"))
                .evaluate(
                    ctxWithPnl(realized = BigDecimal("10"), unrealizedTotal = BigDecimal("5")),
                ) as Value.Num
        assertThat(v.v).isEqualByComparingTo("15")
    }

    @Test
    fun `unsupported ACCOUNT field is rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(AccountRef("drawdown")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ACCOUNT equity reads from pnl view`() {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun total(): BigDecimal = BigDecimal.ZERO

                override fun equity(): BigDecimal = BigDecimal("10250")

                override fun balance(): BigDecimal = BigDecimal("10000")
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(pnl = pnl),
            )
        val v = ExprCompiler().compile(AccountRef("equity")).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("10250")
    }

    @Test
    fun `ACCOUNT balance reads from pnl view`() {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun total(): BigDecimal = BigDecimal.ZERO

                override fun equity(): BigDecimal = BigDecimal("10250")

                override fun balance(): BigDecimal = BigDecimal("10000")
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(pnl = pnl),
            )
        val v = ExprCompiler().compile(AccountRef("balance")).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("10000")
    }

    @Test
    fun `POSITION reads signed quantity from positions view`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    if (symbol == "BTCUSDT") {
                        com.qkt.positions.Position("BTCUSDT", BigDecimal("2.5"), BigDecimal("100"))
                    } else {
                        null
                    }

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
                        .PositionRef("btc"),
                ).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("2.5")
    }

    @Test
    fun `POSITION on unknown symbol is zero`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v =
            ExprCompiler()
                .compile(
                    com.qkt.dsl.ast
                        .PositionRef("btc"),
                ).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("0")
    }

    @Test
    fun `POSITION on unknown stream alias errors at evaluation`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        assertThatThrownBy {
            ExprCompiler()
                .compile(
                    com.qkt.dsl.ast
                        .PositionRef("btc"),
                ).evaluate(ec)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `POSITION_AVG_PRICE reads avg entry price`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    com.qkt.positions.Position("BTCUSDT", BigDecimal("1"), BigDecimal("105.50"))

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
    fun `POSITION_MFE reads from positions view mfeFor`() {
        val pos =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions(): Map<String, Position> = emptyMap()

                override fun mfeFor(symbol: String): BigDecimal =
                    if (symbol == "BTCUSDT") BigDecimal("12.34") else BigDecimal.ZERO
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
    fun `OPEN_ORDERS state is rejected in 11c1`() {
        assertThatThrownBy {
            ExprCompiler()
                .compile(
                    com.qkt.dsl.ast
                        .StateAccessor(com.qkt.dsl.ast.StateSource.OPEN_ORDERS, "btc"),
                )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
