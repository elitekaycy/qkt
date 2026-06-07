package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeQty
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

class ActionCompilerTest {
    private val candle =
        Candle(
            "BACKTEST:BTCUSDT",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            0L,
            1L,
        )
    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `BUY emits Signal Buy with SizeQty`() {
        val action =
            Buy(
                stream = "btc",
                opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("2"))), orderType = Market),
            )
        val sigs = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)
        assertThat(sigs).containsExactly(Signal.Buy("BACKTEST:BTCUSDT", BigDecimal("2")))
    }

    @Test
    fun `SELL emits Signal Sell`() {
        val action =
            Sell(stream = "btc", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("3")))))
        val sigs = ActionCompiler(ExprCompiler()).compile(action).invoke(ctx)
        assertThat(sigs).containsExactly(Signal.Sell("BACKTEST:BTCUSDT", BigDecimal("3")))
    }

    @Test
    fun `CLOSE of independent legs emits a per-leg close-by-ticket for each`() {
        fun leg(
            id: String,
            side: Side,
            ticket: String,
        ) = PositionLeg(
            legId = id,
            symbol = "BACKTEST:BTCUSDT",
            side = side,
            quantity = BigDecimal("0.25"),
            entryPrice = BigDecimal("100"),
            openedAt = 0L,
            role = LegRole.INDEPENDENT,
            brokerTicket = ticket,
        )
        val legs = listOf(leg("leg-A", Side.BUY, "111"), leg("leg-B", Side.SELL, "222"))
        val view =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions(): Map<String, Position> = emptyMap()

                override fun legsFor(symbol: String) = if (symbol == "BACKTEST:BTCUSDT") legs else emptyList()
            }
        val ctx2 =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = view),
            )

        val sigs = ActionCompiler(ExprCompiler()).compile(Close("btc")).invoke(ctx2)

        assertThat(sigs.first()).isInstanceOf(Signal.CancelPendingForSymbol::class.java)
        val closes = sigs.filterIsInstance<Signal.Submit>().map { it.request as OrderRequest.Market }
        assertThat(closes).hasSize(2)
        assertThat(closes.map { it.closesLegId }).containsExactlyInAnyOrder("leg-A", "leg-B")
        assertThat(closes.map { it.closesTicket }).containsExactlyInAnyOrder("111", "222")
        // Long leg closes with a SELL, short leg with a BUY.
        assertThat(closes.first { it.closesLegId == "leg-A" }.side).isEqualTo(Side.SELL)
        assertThat(closes.first { it.closesLegId == "leg-B" }.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `Buy without sizing is rejected`() {
        assertThatThrownBy {
            ActionCompiler(ExprCompiler()).compile(Buy(stream = "btc", opts = ActionOpts()))
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
