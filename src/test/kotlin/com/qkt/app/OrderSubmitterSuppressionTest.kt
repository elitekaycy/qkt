package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderSubmitterSuppressionTest {
    private val clock = FixedClock(5L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val positions = StrategyPositionTracker()
    private val sent = mutableListOf<OrderRequest>()
    private val rejected = mutableListOf<Pair<String, String>>()
    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }
    private val refuseAll =
        object : RiskRule {
            override fun evaluate(
                request: OrderRequest,
                positions: PositionProvider,
            ): Decision = Decision.Reject("max exposure")
        }

    init {
        bus.subscribe<OrderEvent> { sent += it.request }
        bus.subscribe<RiskRejectedEvent> { rejected += it.request.id to it.reason }
    }

    private fun submitter(
        rules: List<RiskRule> = emptyList(),
        bookScale: BigDecimal = BigDecimal.ONE,
    ) = OrderSubmitter(
        SequentialIdGenerator(prefix = "t-"),
        clock,
        bus,
        RiskEngine(rules, positions.account),
        positions.account,
        positions,
        MarketPriceTracker(),
        ExitHookManager(NoopStatePersistor()),
        { PositionAccountingMode.NETTING },
        { bookScale },
    )

    private fun leg(
        id: String,
        side: Side,
    ) = OrderRequest.Market(id, "XAUUSD", side, BigDecimal("0.1"), TimeInForce.GTC, 0L, "st")

    private val group = Signal.SubmitGroup("ps-1", "ps", listOf(leg("a", Side.BUY), leg("b", Side.SELL)))

    @Test
    fun `a book scaled to zero suppresses every leg of a structure`() {
        val ctx = testStrategyContext(strategyId = "st")

        submitter(bookScale = BigDecimal.ZERO).submitGroup("st", strategy, ctx, group)

        assertThat(sent).isEmpty()
        val reason = "book de-risk: new risk suppressed"
        assertThat(rejected).containsExactly("a" to reason, "b" to reason)
        assertThat(ctx.submissions.suppressed).isEqualTo(1)
    }

    @Test
    fun `a structure the risk engine refuses is refused leg by leg`() {
        val ctx = testStrategyContext(strategyId = "st")

        submitter(rules = listOf(refuseAll)).submitGroup("st", strategy, ctx, group)

        assertThat(sent).isEmpty()
        assertThat(rejected.map { it.first }).containsExactly("a", "b")
        assertThat(rejected.map { it.second }.distinct()).hasSize(1)
        assertThat(ctx.submissions.suppressed).isEqualTo(1)
    }

    @Test
    fun `a submit signal carrying its own request is approved and sent`() {
        val ctx = testStrategyContext(strategyId = "st")

        submitter().submit("st", strategy, ctx, Signal.Submit(leg("own", Side.BUY)))

        assertThat(sent.single().symbol).isEqualTo("XAUUSD")
        assertThat(ctx.submissions.accepted).isEqualTo(1)
    }
}
