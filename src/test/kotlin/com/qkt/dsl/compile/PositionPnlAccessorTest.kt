package com.qkt.dsl.compile

import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `POSITION.<s>.realized_pnl` and `.unrealized_pnl` read the strategy P&L view; non-position sources are refused. */
class PositionPnlAccessorTest {
    private val one = BigDecimal.ONE
    private val pnl =
        object : StrategyPnLView {
            override fun realized(): BigDecimal = BigDecimal("12.5")

            override fun unrealizedFor(symbol: String): BigDecimal = if (symbol == "BACKTEST:BTCUSDT") BigDecimal("-3") else BigDecimal.ZERO

            override fun unrealizedTotal(): BigDecimal = BigDecimal("-3")

            override fun total(): BigDecimal = BigDecimal("9.5")

            override fun equity(): BigDecimal = BigDecimal("9.5")

            override fun balance(): BigDecimal = BigDecimal("12.5")
        }
    private val ctx =
        EvalContext(
            Candle("BACKTEST:BTCUSDT", one, one, one, one, BigDecimal.ZERO, 0L, 1L),
            mapOf("b" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            emptyMap(),
            testStrategyContext(pnl = pnl),
        )

    private fun read(source: StateSource) = (StateAccessorCompiler.compile(StateAccessor(source, "b")).evaluate(ctx) as Value.Num).v

    @Test
    fun `realized reads the strategy total and unrealized reads the stream's symbol`() {
        assertThat(read(StateSource.POSITION_REALIZED_PNL)).isEqualByComparingTo("12.5")
        assertThat(read(StateSource.POSITION_UNREALIZED_PNL)).isEqualByComparingTo("-3")
    }

    @Test
    fun `an unrealized read on an undeclared stream fails`() {
        val missing = StateAccessorCompiler.compile(StateAccessor(StateSource.POSITION_UNREALIZED_PNL, "eth"))

        assertThatThrownBy { missing.evaluate(ctx) }.hasMessageContaining("Unknown stream alias: eth")
    }

    @Test
    fun `a source that is not a position field is refused at compile time`() {
        assertThatThrownBy { StateAccessorCompiler.compile(StateAccessor(StateSource.ACCOUNT, "b")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("StateAccessor source ACCOUNT is not supported")
    }
}
