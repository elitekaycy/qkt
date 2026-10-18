package com.qkt.broker

import com.qkt.app.IntrabarFill
import com.qkt.backtest.BarFills
import com.qkt.backtest.NoTickBars
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.research.BarResolvedFeed
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tick-resolved `--bars` fills on a bar the store has no ticks for (#1439): a stop the bar trades through
 * fills at its level, as `--no-tick-fills` fills it, never at the bar's synthetic low; a bar with ticks
 * still fills on its real print. The numbers are the EURUSD 2024-04-10 12:30 CPI bar.
 */
class NoTickBarLevelFillTest {
    private val symbol = "EURUSD"

    private fun bar(
        start: Long,
        o: String,
        h: String,
        l: String,
        c: String,
    ) = Candle(symbol, Money.of(o), Money.of(h), Money.of(l), Money.of(c), Money.of("0"), start, start + 1000)

    private val calm = bar(0, "1.08700", "1.08720", "1.08600", "1.08650")
    private val cpi = bar(1000, "1.08635", "1.08700", "1.077885", "1.07900")
    private val calmTicks = listOf(Tick(symbol, Money.of("1.08700"), 0), Tick(symbol, Money.of("1.08650"), 999))
    private val cpiTicks =
        listOf(
            Tick(symbol, Money.of("1.08635"), 1000),
            Tick(symbol, Money.of("1.07786"), 1300),
            Tick(symbol, Money.of("1.07900"), 1999),
        )

    /** The sell stop's fill price over [bars], replayed with ticks only for the bars starting in [ticked]. */
    private fun stopFill(
        bars: List<Candle>,
        ticked: Set<Long>,
        broker: (EventBus, MarketPriceTracker, BarFills) -> (Tick) -> Unit,
    ): BigDecimal {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        val noTickBars = NoTickBars(positionSign = { 1 })
        val onTick = broker(bus, tracker, BarFills(emptySet(), noTickBars))
        val ticks = (calmTicks + cpiTicks).filter { it.timestamp / 1000 * 1000 in ticked }
        val slice = { _: String, from: Long, to: Long -> ticks.filter { it.timestamp in from until to }.asSequence() }
        val allTicks = { _: String, _: BigDecimal, _: BigDecimal, _: BigDecimal -> IntrabarFill.ALL_TICKS }
        val feed = BarResolvedFeed(mapOf(symbol to bars.asSequence()), slice, allTicks, null, noTickBars)
        generateSequence { feed.next() }.forEach { tick ->
            tracker.update(tick.symbol, tick.price)
            onTick(tick)
        }
        return fills.single().price
    }

    private val sellStop =
        OrderRequest.Stop(
            id = "sl",
            symbol = symbol,
            side = Side.SELL,
            quantity = Money.of("1"),
            stopPrice = Money.of("1.08478"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    private val paper = { bus: EventBus, tracker: MarketPriceTracker, barFills: BarFills ->
        val broker = PaperBroker(bus, FixedClock(0L), tracker, fillAtTriggerPrice = barFills::at)
        broker.submit(sellStop)
        broker::onTick
    }

    private val mt5 = { bus: EventBus, tracker: MarketPriceTracker, barFills: BarFills ->
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                eurusd,
                syntheticSpreadPoints = 0,
                fillAtTriggerPrice = barFills::at,
            )
        sim.submit(sellStop)
        sim::onTick
    }

    @Test
    fun `paper fills a stop inside a bar without ticks at its level, not the bar's low`() {
        assertThat(stopFill(listOf(calm, cpi), ticked = setOf(0L), broker = paper)).isEqualByComparingTo("1.08478")
    }

    @Test
    fun `paper still fills a stop inside a bar with ticks at the real print`() {
        assertThat(stopFill(listOf(calm, cpi), ticked = setOf(0L, 1000L), broker = paper))
            .isEqualByComparingTo("1.07786")
    }

    @Test
    fun `paper fills a no-tick bar that opens beyond the stop at its open`() {
        val gap = bar(1000, "1.08400", "1.08450", "1.077885", "1.07900")
        assertThat(stopFill(listOf(calm, gap), ticked = setOf(0L), broker = paper)).isEqualByComparingTo("1.08400")
    }

    @Test
    fun `mt5-sim fills a stop inside a bar without ticks at its level, not the bar's low`() {
        assertThat(stopFill(listOf(calm, cpi), ticked = setOf(0L), broker = mt5)).isEqualByComparingTo("1.08478")
    }

    @Test
    fun `mt5-sim still fills a stop inside a bar with ticks at the real print`() {
        assertThat(stopFill(listOf(calm, cpi), ticked = setOf(0L, 1000L), broker = mt5)).isEqualByComparingTo("1.07786")
    }

    private val eurusd =
        object : InstrumentRegistry {
            private val meta =
                InstrumentMeta(
                    qktSymbol = symbol,
                    contractSize = BigDecimal("100000"),
                    volumeStep = BigDecimal("0.01"),
                    volumeMin = BigDecimal("0.01"),
                    volumeMax = null,
                    pointSize = BigDecimal("0.00001"),
                    digits = 5,
                    tradeStopsLevelPoints = 0,
                )

            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == symbol) meta else null
        }
}
