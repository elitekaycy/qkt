package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A stray far quote on a thin book (bot2 soak, a 0.001 short of a dated future: one ask near 240,505 against a market
 * near 79,000 drew a -159 unrealized for one sample): the venue's mark passes the gate, so nothing judged the ask a
 * short is marked at. The gate now drops such a side, and the position marks at the tick's price until it is sane.
 */
class TradingPipelineAbsurdQuoteTest {
    private val symbol = "PAPER_DERIBIT:BTC_USDC_30OCT26"
    private val clock = FixedClock(time = 0L)
    private val prices = MarketPriceTracker()
    private val book = StrategyPositionTracker()
    private val pnl = PnLCalculator(book.account, prices)

    private fun pipeline(gate: MarketDataGate?): TradingPipeline {
        val strategyPnL = com.qkt.pnl.StrategyPnL(book, prices)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        return TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = MonotonicSequenceGenerator(),
            priceTracker = prices,
            positions = book.account,
            pnl = pnl,
            strategyPositions = book,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = PaperBroker(bus, clock, prices),
            engine = Engine(bus, prices),
            strategies = emptyList(),
            riskEngine = RiskEngine(rules = emptyList(), positions = book.account),
            riskState = com.qkt.risk.RiskState(pnl, strategyPnL, clock, bus),
            mode = Mode.LIVE,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
            marketDataGate = gate,
        )
    }

    private fun holding(
        side: Side,
        quantity: String,
        entry: String,
    ) {
        book.applyFill(
            BrokerEvent.OrderFilled("o1", null, symbol, side, BigDecimal(entry), BigDecimal(quantity), "s"),
            LegIntent.Open("leg-1", LegRole.PRIMARY),
        )
    }

    private fun tick(
        price: String,
        bid: String?,
        ask: String?,
    ) = Tick(symbol, Money.of(price), 1L, bid = bid?.let(Money::of), ask = ask?.let(Money::of))

    @Test
    fun `one absurd ask does not move a short's unrealized, and the next sane quote marks it again`() {
        holding(Side.SELL, "0.001", "81165")
        val pipeline = pipeline(MarketDataGate(clock))

        pipeline.ingest(tick("79000", "78990", "79010"))
        assertThat(pnl.unrealizedFor(symbol)).isEqualByComparingTo("2.155")

        pipeline.ingest(tick("79000", "78990", "240505"))
        assertThat(prices.closingMark(symbol, -1)).isEqualByComparingTo("79000")
        assertThat(pnl.unrealizedFor(symbol)).isEqualByComparingTo("2.165")

        pipeline.ingest(tick("79000", "78990", "79020"))
        assertThat(pnl.unrealizedFor(symbol)).isEqualByComparingTo("2.145")
    }

    @Test
    fun `an absurd bid does not move a long's unrealized`() {
        holding(Side.BUY, "0.001", "79000")
        val pipeline = pipeline(MarketDataGate(clock))

        pipeline.ingest(tick("79100", "100", "79110"))

        assertThat(prices.closingMark(symbol, 1)).isEqualByComparingTo("79100")
        assertThat(pnl.unrealizedFor(symbol)).isEqualByComparingTo("0.1")
    }

    @Test
    fun `a wide but plausible book still marks a short at its ask and a long at its bid`() {
        val pipeline = pipeline(MarketDataGate(clock))

        pipeline.ingest(tick("79000", "77000", "81000"))

        assertThat(prices.closingMark(symbol, -1)).isEqualByComparingTo("81000")
        assertThat(prices.closingMark(symbol, 1)).isEqualByComparingTo("77000")
    }

    @Test
    fun `a symbol whose prices are not judged keeps its wide book, as an option's`() {
        val pipeline = pipeline(MarketDataGate(clock, judgesOutliers = { false }))

        pipeline.ingest(tick("100", "50", "300"))

        assertThat(prices.closingMark(symbol, -1)).isEqualByComparingTo("300")
    }

    @Test
    fun `a backtest has no gate, so a replay trades exactly the data it was given`() {
        val pipeline = pipeline(null)

        pipeline.ingest(tick("79000", "78990", "240505"))

        assertThat(prices.closingMark(symbol, -1)).isEqualByComparingTo("240505")
    }
}
