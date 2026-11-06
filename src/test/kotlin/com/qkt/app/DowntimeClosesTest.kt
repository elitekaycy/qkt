package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerDeal
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PaperBroker
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.engine.Engine
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.persistence.PersistedLeg
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DowntimeClosesTest {
    private val clock = FixedClock(time = 50_000L)

    private class DealsBroker(
        private val deals: () -> List<BrokerDeal>,
    ) : Broker {
        val windows = mutableListOf<Pair<Long, Long>>()
        override val name = "venue"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) {}

        override fun deals(
            from: Long,
            to: Long,
        ): List<BrokerDeal> {
            windows += from to to
            return deals()
        }
    }

    private fun deal(
        ticket: String,
        entry: String,
        profit: String,
        fee: String? = null,
    ) = BrokerDeal(
        broker = "VENUE",
        dealTicket = "d-$ticket-$entry",
        positionTicket = ticket,
        orderTicket = null,
        symbol = "VENUE:XAUUSD",
        side = Side.SELL,
        entry = entry,
        qty = BigDecimal.ONE,
        price = BigDecimal("2000"),
        profit = BigDecimal(profit),
        commission = BigDecimal("-1"),
        swap = BigDecimal("-0.5"),
        magic = null,
        comment = null,
        ts = 40_000L,
        fee = fee?.let(::BigDecimal),
    )

    private fun leg(
        id: String,
        ticket: String?,
    ) = PersistedLeg(id, null, LegRole.PRIMARY, Side.BUY, "XAUUSD", BigDecimal.ONE, BigDecimal("1990"), 10_000L, ticket)

    private fun compiled(): DslCompiledStrategy =
        AstCompiler().compile(
            (
                Dsl.parse(
                    """
                    STRATEGY downtime VERSION 1

                    SYMBOLS
                        gold = BACKTEST:XAUUSD EVERY 1m

                    RULES
                        WHEN POSITION.gold = 0
                        THEN BUY gold SIZING 0.01
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value,
        ) as DslCompiledStrategy

    private fun pipeline(bus: EventBus): TradingPipeline {
        val prices = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
        val strategyPnl = StrategyPnL(strategyPositions, prices)
        return TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = MonotonicSequenceGenerator(),
            priceTracker = prices,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnl,
            bus = bus,
            broker = PaperBroker(bus, clock, prices),
            engine = Engine(bus, prices),
            strategies = emptyList(),
            riskEngine = RiskEngine(rules = emptyList(), positions = positions),
            riskState = RiskState(pnl, strategyPnl, clock, bus),
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    @Test
    fun `the venue realized on a downtime close is booked once per leg as a reconcile`() {
        val broker =
            DealsBroker {
                listOf(
                    deal("9001", "IN", "0"),
                    deal("9001", "OUT", "-40", fee = "-0.6"),
                    deal("9001", "OUT_BY", "5"),
                    deal("7777", "OUT", "100"),
                )
            }
        val closes = DowntimeCloses(broker, clock)
        closes.onLegRetired("alpha", leg("leg-1", "9001"))
        closes.onLegRetired("beta", leg("leg-2", "missing"))

        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val booked = mutableListOf<FillAccountedEvent>()
        bus.subscribe<FillAccountedEvent> { booked += it }
        closes.bookInto(pipeline(bus))

        assertThat(broker.windows).containsExactly(9_999L to 50_000L, 9_999L to 50_000L)
        // -40 -1 -0.5 -0.6 on the OUT plus 5 -1 -0.5 on the OUT_BY; the IN and the other ticket are ignored.
        val reconcile = booked.single()
        assertThat(reconcile.kind).isEqualTo(FillAccountingKind.RECONCILE)
        assertThat(reconcile.legId).isEqualTo("leg-1")
        assertThat(reconcile.netAccountRealized).isEqualByComparingTo("-38.6")
    }

    @Test
    fun `a venue whose deal history fails retires the leg without booking`() {
        val closes = DowntimeCloses(DealsBroker { error("history down") }, clock)
        closes.onLegRetired("alpha", leg("leg-1", "9001"))

        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val booked = mutableListOf<FillAccountedEvent>()
        bus.subscribe<FillAccountedEvent> { booked += it }
        closes.bookInto(pipeline(bus))

        assertThat(booked).isEmpty()
    }

    @Test
    fun `rule edges are cleared only for dsl strategies that lost a position`() {
        val real = compiled()
        val cleared = mutableListOf<String>()

        fun recording(id: String): DslCompiledStrategy =
            object : DslCompiledStrategy by real {
                override fun clearRuleEdges() {
                    cleared += id
                }
            }
        val throwing =
            object : DslCompiledStrategy by real {
                override fun clearRuleEdges() {
                    cleared += "boom"
                    error("edges locked")
                }
            }
        val plain =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {}
            }
        val closes = DowntimeCloses(DealsBroker { emptyList() }, clock)
        listOf("lost", "faulty", "plain", "unbound").forEach { closes.onLegRetired(it, leg("l-$it", null)) }

        closes.clearRuleEdges(
            listOf("kept" to recording("kept"), "lost" to recording("lost"), "faulty" to throwing, "plain" to plain),
        )

        assertThat(cleared).containsExactly("lost", "boom")
    }
}
