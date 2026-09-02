package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.TradeEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the exact accounting every consumer sees for one netting round trip, a partial slice
 * and a financing accrual — both P&L accumulators, the daily tracker, trade history and the
 * accounted-fill event. Stage C turns these numbers into a fold over one event stream; they must
 * not move.
 */
class LedgerAccountingCharacterizationTest {
    private class Harness {
        val clock = FixedClock(time = 0L)
        val sequencer = MonotonicSequenceGenerator()
        val prices = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
        val strategyPnl = StrategyPnL(strategyPositions, prices)
        val bus = EventBus(clock, sequencer)
        val riskState = RiskState(pnl, strategyPnl, clock, bus)
        val accounted = mutableListOf<FillAccountedEvent>()
        val trades = mutableListOf<TradeEvent>()
        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = SequentialIdGenerator(),
                sequencer = sequencer,
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
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
            )

        init {
            strategyPnl.setStartingBalance("alpha", BigDecimal("10000"))
            bus.subscribe<FillAccountedEvent> { accounted.add(it) }
            bus.subscribe<TradeEvent> { trades.add(it) }
        }

        fun fill(
            id: String,
            side: Side,
            qty: String,
            price: String,
            ts: Long,
        ) = bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = id,
                brokerOrderId = "t-$id",
                symbol = "XAUUSD",
                side = side,
                price = BigDecimal(price),
                quantity = BigDecimal(qty),
                strategyId = "alpha",
                timestamp = ts,
            ),
        )
    }

    @Test
    fun `a netting round trip books the same realized into every accumulator once`() {
        val h = Harness()
        h.prices.update("XAUUSD", BigDecimal("2000"))
        h.fill("e1", Side.BUY, "2", "2000", 1_000L)
        h.prices.update("XAUUSD", BigDecimal("2010"))
        assertThat(h.strategyPnl.unrealizedFor("alpha", "XAUUSD")).isEqualByComparingTo("20")
        assertThat(h.pnl.unrealizedTotal()).isEqualByComparingTo("20")
        assertThat(h.strategyPnl.equityFor("alpha")).isEqualByComparingTo("10020")

        h.fill("x1", Side.SELL, "2", "2010", 2_000L)

        assertThat(h.pnl.realizedTotal()).isEqualByComparingTo("20")
        assertThat(h.strategyPnl.realizedFor("alpha")).isEqualByComparingTo("20")
        assertThat(h.strategyPnl.balanceFor("alpha")).isEqualByComparingTo("10020")
        assertThat(h.riskState.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("20")
        assertThat(h.riskState.dailyPnLTracker.realizedToday("alpha")).isEqualByComparingTo("20")
        assertThat(h.pipeline.tradeHistory.lastTradePnl("alpha")).isEqualByComparingTo("20")
        assertThat(h.positions.positionFor("XAUUSD")).isNull()
        assertThat(h.strategyPositions.positionFor("alpha", "XAUUSD")).isNull()
        assertThat(h.accounted).hasSize(2)
        val close = h.accounted.last()
        assertThat(close.netAccountRealized).isEqualByComparingTo("20")
        assertThat(close.netStrategyAccountRealized).isEqualByComparingTo("20")
        assertThat(close.reducedExposure).isTrue()
        assertThat(close.partial).isFalse()
        assertThat(close.accountPositionAfter).isNull()
        assertThat(close.strategyPositionBefore!!.quantity).isEqualByComparingTo("2")
        assertThat(h.trades.map { it.trade.quantity }).allSatisfy { assertThat(it).isEqualByComparingTo("2") }
    }

    @Test
    fun `a partial slice is accounted at its own quantity and cumulative`() {
        val h = Harness()
        h.prices.update("XAUUSD", BigDecimal("2000"))
        h.bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "e1",
                brokerOrderId = "t-e1",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = BigDecimal("2000"),
                quantity = BigDecimal("0.4"),
                cumulativeFilled = BigDecimal("0.4"),
                strategyId = "alpha",
                timestamp = 1_000L,
            ),
        )
        val slice = h.accounted.single()
        assertThat(slice.partial).isTrue()
        assertThat(slice.cumulativeFilled).isEqualByComparingTo("0.4")
        assertThat(slice.netAccountRealized).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(h.positions.positionFor("XAUUSD")!!.quantity).isEqualByComparingTo("0.4")
        assertThat(h.strategyPositions.positionFor("alpha", "XAUUSD")!!.quantity).isEqualByComparingTo("0.4")
        assertThat(
            h.trades
                .single()
                .trade.quantity,
        ).isEqualByComparingTo("0.4")
    }

    @Test
    fun `a financing accrual reaches both accumulators and the daily tracker`() {
        val h = Harness()
        h.pipeline.applyFinancing("alpha", BigDecimal("-3.5"))

        assertThat(h.pnl.realizedTotal()).isEqualByComparingTo("-3.5")
        assertThat(h.strategyPnl.realizedFor("alpha")).isEqualByComparingTo("-3.5")
        assertThat(h.riskState.dailyPnLTracker.realizedToday("alpha")).isEqualByComparingTo("-3.5")
        assertThat(h.strategyPnl.equityFor("alpha")).isEqualByComparingTo("9996.5")
        val accrual = h.accounted.single()
        assertThat(accrual.kind).isEqualTo(com.qkt.events.FillAccountingKind.FINANCING)
        assertThat(accrual.netAccountRealized).isEqualByComparingTo("-3.5")
        assertThat(accrual.legAction).isNull()
        assertThat(h.trades).isEmpty()
    }

    @Test
    fun `a boot reconcile books lifetime realized but stays out of today's loss budget`() {
        val h = Harness()
        h.pipeline.applyReconciledRealized("alpha", BigDecimal("12.25"), legId = "leg-7")

        assertThat(h.pnl.realizedTotal()).isEqualByComparingTo("12.25")
        assertThat(h.strategyPnl.realizedFor("alpha")).isEqualByComparingTo("12.25")
        assertThat(h.strategyPnl.balanceFor("alpha")).isEqualByComparingTo("10012.25")
        assertThat(h.riskState.dailyPnLTracker.realizedToday("alpha")).isEqualByComparingTo(BigDecimal.ZERO)
        val reconcile = h.accounted.single()
        assertThat(reconcile.kind).isEqualTo(com.qkt.events.FillAccountingKind.RECONCILE)
        assertThat(reconcile.legId).isEqualTo("leg-7")
        assertThat(reconcile.orderId).isEqualTo("reconcile:leg-7")
    }

    @Test
    fun `an execution's accounted event carries the fill's time and kind`() {
        val h = Harness()
        h.clock.time = 5_000L
        h.fill("e1", Side.BUY, "1", "2000", 1_234L)
        val open = h.accounted.single()
        assertThat(open.kind).isEqualTo(com.qkt.events.FillAccountingKind.EXECUTION)
        // The bus stamps every event it carries, so in the backtest the fill's time is the
        // clock's; executedAt is whatever the fill carried when it was accounted.
        assertThat(open.executedAt).isEqualTo(5_000L)
        assertThat(h.pipeline.tradeHistory.lastTradePnl("alpha")).isNull()
    }
}
