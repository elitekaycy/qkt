package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.At
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StackPlan
import com.qkt.execution.TimeInForce
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The halt gate on engine-held submissions: risk that only reduces the account's position still goes out. */
class PipelineOrderManagerHaltGateTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
    private val riskState = RiskState.noOp(clock)
    private val longFive =
        object : PositionProvider {
            override fun positionFor(symbol: String) = Position(symbol, BigDecimal("5"), BigDecimal("50000"))

            override fun allPositions() = mapOf("BTCUSDT" to positionFor("BTCUSDT"))
        }
    private val manager =
        pipelineOrderManager(
            broker,
            bus,
            MarketPriceTracker(),
            clock,
            NoopStatePersistor(),
            StrategyPositionTracker(),
            longFive,
            riskState,
            Mode.BACKTEST,
            NoopInstrumentRegistry,
            { _, _ -> },
            { PositionAccountingMode.NETTING },
        )

    private fun stack(
        id: String,
        side: Side,
    ) = OrderRequest.Stack(
        id = id,
        symbol = "BTCUSDT",
        side = side,
        quantity = BigDecimal("0.3"),
        plan =
            StackPlan(
                listOf(
                    LayerSpec(1, SizeQty(NumLit(BigDecimal("0.1"))), Market, Immediate),
                    LayerSpec(
                        2,
                        SizeQty(NumLit(BigDecimal("0.2"))),
                        Market,
                        At(
                            BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("100"))),
                            StackDirection.TRADE_DIRECTION,
                        ),
                    ),
                ),
            ),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        strategyId = "alpha",
    )

    @Test
    fun `a halted strategy's layer that adds to the position is refused with the halt reason`() {
        val refused = mutableListOf<RiskRejectedEvent>()
        bus.subscribe<RiskRejectedEvent> { refused += it }
        manager.submit(stack("up", Side.BUY))
        riskState.haltStrategy("alpha", "daily loss")

        broker.emitFill(broker.submits.single(), price = Money.of("50000"))

        assertThat(manager.getOrder("up-l2")!!.state).isEqualTo(OrderState.REJECTED)
        assertThat(refused.single().reason).isEqualTo("halted: daily loss")
    }

    @Test
    fun `a halted strategy's layer that only reduces the account's long still goes out`() {
        val refused = mutableListOf<RiskRejectedEvent>()
        bus.subscribe<RiskRejectedEvent> { refused += it }
        manager.submit(stack("down", Side.SELL))
        riskState.haltStrategy("alpha", "daily loss")

        broker.emitFill(broker.submits.single(), price = Money.of("50000"))

        assertThat(refused).isEmpty()
        assertThat(manager.getOrder("down-l2")!!.state).isNotEqualTo(OrderState.REJECTED)
    }
}
