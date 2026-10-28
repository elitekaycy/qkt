package com.qkt.broker

import com.qkt.backtest.ReplaySessions
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.PerSymbolCalendar
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A resting order the venue holds does not fill on a quote outside its symbol's session (Exness prints US stock CFDs
 * from 06:00 New York but trades them from the regular open): it fills on the first in-session price, as at the open.
 */
class SessionClosedFillTest {
    private val stock = requireNotNull(TradingCalendar.named("nyse[09:30-15:45]"))
    private val calendars = mapOf("X:AAPL" to stock, "X:BTCUSD" to TradingCalendar.crypto())
    private val calendar = PerSymbolCalendar(stock, calendars.keys.toList(), calendars::getValue)
    private val sessions = ReplaySessions(calendar)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val clock = FixedClock(ms("2026-10-27T19:00:00Z"))
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val tracker = MarketPriceTracker()
    private val fills = mutableListOf<Pair<Long, BigDecimal>>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it.timestamp to it.price) }
        tracker.update("X:AAPL", Money.of("150"))
        tracker.update("X:BTCUSD", Money.of("60000"))
    }

    private fun meta(symbol: String) =
        InstrumentMeta(
            qktSymbol = symbol,
            contractSize = BigDecimal.ONE,
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )

    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) = meta(qktSymbol)
        }

    private fun buyLimit(
        symbol: String,
        price: String,
    ) = OrderRequest.Limit(
        id = "lim-$symbol",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
        strategyId = "s",
    )

    private fun tick(
        onTick: (Tick) -> Unit,
        symbol: String,
        iso: String,
        price: String,
    ) {
        clock.time = ms(iso)
        tracker.update(symbol, Money.of(price))
        onTick(Tick(symbol, Money.of(price), clock.time))
    }

    /** A buy limit at 149 placed the afternoon before; the pre-market prints touch it, the regular session opens below. */
    private fun premarketTouch(
        broker: Broker,
        onTick: (Tick) -> Unit,
    ) {
        broker.submit(buyLimit("X:AAPL", "149"))
        tick(onTick, "X:AAPL", "2026-10-28T11:00:00Z", "148.00")
        tick(onTick, "X:AAPL", "2026-10-28T13:29:59Z", "148.20")
        assertThat(fills).isEmpty()
        tick(onTick, "X:AAPL", "2026-10-28T13:30:00Z", "148.50")
    }

    @Test
    fun `paper holds a pre-market touch and fills the limit on the first in-session tick`() {
        val broker = PaperBroker(bus, clock, tracker, calendar = calendar, session = sessions::acceptsOrders)

        premarketTouch(broker, broker::onTick)

        assertThat(fills).containsExactly(ms("2026-10-28T13:30:00Z") to Money.of("148.50"))
        assertThat(broker.acceptsOrders("X:AAPL", ms("2026-10-28T11:00:00Z"))).isFalse()
    }

    @Test
    fun `mt5-sim holds a pre-market touch and fills the limit on the first in-session tick`() {
        val sim = MT5BrokerSimulator(bus, clock, tracker, registry, session = sessions::acceptsOrders)

        premarketTouch(sim, sim::onTick)

        assertThat(fills.map { it.first }).containsExactly(ms("2026-10-28T13:30:00Z"))
        assertThat(sim.acceptsOrders("X:AAPL", ms("2026-10-28T11:00:00Z"))).isFalse()
    }

    @Test
    fun `an in-session touch and a 24-7 symbol fill as before`() {
        val broker = PaperBroker(bus, clock, tracker, calendar = calendar, session = sessions::acceptsOrders)
        broker.submit(buyLimit("X:AAPL", "149"))
        broker.submit(buyLimit("X:BTCUSD", "59000"))

        tick(broker::onTick, "X:AAPL", "2026-10-27T19:10:00Z", "148.90")
        tick(broker::onTick, "X:BTCUSD", "2026-10-31T15:00:00Z", "58900")

        assertThat(fills).containsExactly(
            ms("2026-10-27T19:10:00Z") to Money.of("148.90"),
            ms("2026-10-31T15:00:00Z") to Money.of("58900"),
        )
    }

    @Test
    fun `a broker given no session accepts orders at any time`() {
        val broker = PaperBroker(bus, clock, tracker, calendar = calendar)

        assertThat(broker.acceptsOrders("X:AAPL", ms("2026-10-31T15:00:00Z"))).isTrue()
    }
}
