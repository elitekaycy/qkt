package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.Close
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** CLOSE closes a short PRIMARY leg by ticket and refuses a basket whose constituent is undeclared. */
class CloseByLegTest {
    private val one = BigDecimal.ONE
    private val shortPrimary =
        PositionLeg("p1", "BACKTEST:XAUUSD", Side.SELL, BigDecimal("2"), one, 0L, LegRole.PRIMARY, brokerTicket = "t-9")

    private val positions =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? = null

            override fun allPositions(): Map<String, Position> = emptyMap()

            override fun legsFor(symbol: String): List<PositionLeg> =
                if (symbol ==
                    "BACKTEST:XAUUSD"
                ) {
                    listOf(shortPrimary)
                } else {
                    emptyList()
                }
        }

    private val ctx =
        EvalContext(
            Candle("BACKTEST:XAUUSD", one, one, one, one, BigDecimal.ZERO, 0L, 1L),
            mapOf("g" to HubKey("BACKTEST", "XAUUSD", "1m")),
            emptyMap(),
            testStrategyContext(positions = positions),
        )

    private val actions = ActionCompiler(ExprCompiler(), baskets = mapOf("pair" to listOf("g", "missing")))

    @Test
    fun `a short primary leg is bought back by its ticket`() {
        val close =
            actions
                .compile(
                    Close("g"),
                )(ctx)
                .filterIsInstance<Signal.Submit>()
                .single()
                .request as OrderRequest.Market

        assertThat(close.side).isEqualTo(Side.BUY)
        assertThat(close.quantity).isEqualByComparingTo("2")
        assertThat(close.closesTicket).isEqualTo("t-9")
    }

    @Test
    fun `closing a basket with an undeclared constituent fails`() {
        assertThatThrownBy { actions.compile(Close("pair"))(ctx) }
            .hasMessageContaining("Unknown basket constituent alias: missing")
    }
}
