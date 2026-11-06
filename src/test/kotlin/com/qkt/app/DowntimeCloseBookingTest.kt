package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerDeal
import com.qkt.broker.CloseReason
import com.qkt.broker.PaperBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.FillAccountedEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.observe.OrderJournal
import com.qkt.persistence.LegBookReconciler
import com.qkt.persistence.NoopStatePersistor
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * #1664: a leg the venue stopped out while the daemon was down is booked at the next start as a close of that
 * leg — symbol, side, quantity, the venue's price, time and reason — through the fill path a live close takes,
 * so the order journal records it and the accounted event lands on the instrument, exactly once.
 */
class DowntimeCloseBookingTest {
    private val strategy = "soak"
    private val symbol = "XAUUSD"
    private val ticket = "3428826427"
    private val openedAt = 1_762_181_117_000L
    private val closedAt = 1_762_225_333_000L
    private val restartAt = 1_762_357_440_000L

    @Test
    fun `a venue stop during downtime is journaled and accounted as a close of its leg`(
        @TempDir dir: Path,
    ) {
        val clock = FixedClock(time = restartAt)
        val sequencer = MonotonicSequenceGenerator()
        val prices = MarketPriceTracker()
        val persistor = NoopStatePersistor()
        persistor.saveLegBook(strategy, symbol, LegBook(symbol).apply { add(leg()) })
        val strategyPositions = StrategyPositionTracker(persistor)
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
        val strategyPnl = StrategyPnL(strategyPositions, prices)
        val bus = EventBus(clock, sequencer)
        val riskState = RiskState(pnl, strategyPnl, clock, bus)
        val paper = PaperBroker(bus, clock, prices)
        val broker =
            object : Broker by paper {
                override fun deals(
                    from: Long,
                    to: Long,
                ): List<BrokerDeal> = listOf(deal("IN", Side.BUY, "2015.00", "0", openedAt), stopDeal())
            }
        // The start-up reconcile: the venue no longer lists the ticket, so the leg is retired.
        val downtimeCloses = DowntimeCloses(broker, clock)
        val outcome = LegBookReconciler(persistor).reconcile(strategy, symbol, emptyList(), emptySet())
        (outcome as LegBookReconciler.Outcome.Attached).retired.forEach { downtimeCloses.onLegRetired(strategy, it) }
        strategyPositions.preloadFromPersistor(strategy, symbol)
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
                broker = paper,
                engine = Engine(bus, prices),
                strategies = emptyList(),
                riskEngine = RiskEngine(rules = emptyList(), positions = positions),
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
                // An Exness MT5 account hedges: each ticket is its own position, so the close names its leg.
                positionMode = { PositionAccountingMode.HEDGING },
            )
        strategyPnl.setStartingBalance(strategy, BigDecimal("10000"))
        val accounted = mutableListOf<FillAccountedEvent>()
        bus.subscribe<FillAccountedEvent> { accounted.add(it) }
        val journal = OrderJournal(dir, clock)
        OrderJournalWiring(emptyList()).wire(bus, journal)

        downtimeCloses.bookInto(pipeline)
        journal.close()

        val close = accounted.single()
        assertThat(close.orderId).isEqualTo("reconcile:dsl-soak--0")
        assertThat(close.symbol).isEqualTo(symbol)
        assertThat(close.legId).isEqualTo("dsl-soak--0")
        assertThat(close.strategyPositionBefore!!.quantity).isEqualByComparingTo("1.00")
        assertThat(close.strategyPositionAfter?.quantity ?: BigDecimal.ZERO).isEqualByComparingTo("0")
        assertThat(close.reducedExposure).isTrue()
        assertThat(close.netStrategyAccountRealized).isEqualByComparingTo("-15.00")
        assertThat(strategyPnl.realizedFor(strategy)).isEqualByComparingTo("-15.00")
        // Venue history from before this session stays out of today's loss budget.
        assertThat(riskState.dailyPnLTracker.realizedToday(strategy)).isEqualByComparingTo("0")
        assertThat(strategyPositions.positionFor(strategy, symbol)?.quantity ?: BigDecimal.ZERO)
            .isEqualByComparingTo("0")

        val lines = Files.walk(dir).use { s -> s.filter(Files::isRegularFile).toList() }.flatMap(Files::readAllLines)
        assertThat(lines).hasSize(1)
        assertThat(lines.single()).contains(
            "\"kind\":\"filled\"",
            "\"id\":\"reconcile:dsl-soak--0\"",
            "\"broker\":\"$ticket\"",
            "\"symbol\":\"$symbol\"",
            "\"side\":\"SELL\"",
            "\"price\":\"2000.00\"",
            "\"qty\":\"1.00\"",
            "\"exitReason\":\"STOP\"",
            "\"venueTime\":\"$closedAt\"",
        )
        // Exactly once: the retired leg is gone from disk, so the next start retires nothing.
        assertThat(persistor.loadLegBook(strategy, symbol)?.legs.orEmpty()).isEmpty()
        assertThat(LegBookReconciler(persistor).reconcile(strategy, symbol, emptyList(), emptySet()))
            .isEqualTo(LegBookReconciler.Outcome.NothingPersisted)
    }

    private fun leg() =
        PositionLeg(
            legId = "dsl-soak--0",
            symbol = symbol,
            side = Side.BUY,
            quantity = BigDecimal("1.00"),
            entryPrice = BigDecimal("2015.00"),
            openedAt = openedAt,
            role = LegRole.PRIMARY,
            brokerTicket = ticket,
        )

    private fun stopDeal() = deal("OUT", Side.SELL, "2000.00", "-15.00", closedAt).copy(closeReason = CloseReason.STOP)

    private fun deal(
        entry: String,
        side: Side,
        price: String,
        profit: String,
        ts: Long,
    ) = BrokerDeal(
        broker = "PAPER",
        dealTicket = "deal-$entry",
        positionTicket = ticket,
        orderTicket = null,
        symbol = symbol,
        side = side,
        entry = entry,
        qty = BigDecimal("1.00"),
        price = BigDecimal(price),
        profit = BigDecimal(profit),
        commission = BigDecimal.ZERO,
        swap = BigDecimal.ZERO,
        magic = null,
        comment = null,
        ts = ts,
    )
}
