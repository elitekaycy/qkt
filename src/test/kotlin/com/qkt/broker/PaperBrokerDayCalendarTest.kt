package com.qkt.broker

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
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A DAY order expires at the end of its own symbol's trading day, not the day of the strategy's first stream. */
class PaperBrokerDayCalendarTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `day orders on fx and crypto symbols of one strategy expire at their own session ends`() {
        val calendars = mapOf("EURUSD" to TradingCalendar.fxDefault(), "BTCUSD" to TradingCalendar.crypto())
        val calendar = PerSymbolCalendar(TradingCalendar.fxDefault(), listOf("EURUSD", "BTCUSD"), calendars::getValue)
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        tracker.update("BTCUSD", Money.of("60000"))
        val clock = FixedClock(ms("2024-06-12T12:00:00Z"))
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val cancelled = mutableListOf<Pair<String, Long>>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancelled.add(it.clientOrderId to clock.now()) }
        val broker = PaperBroker(bus, clock, tracker, calendar = calendar)
        for ((id, symbol, price) in listOf(Triple("eur", "EURUSD", "1.09"), Triple("btc", "BTCUSD", "59000"))) {
            broker.submit(
                OrderRequest.Limit(
                    id = id,
                    symbol = symbol,
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    limitPrice = Money.of(price),
                    timeInForce = TimeInForce.DAY,
                    timestamp = clock.now(),
                    strategyId = "s",
                ),
            )
        }

        for ((iso, symbol, price) in listOf(
            Triple("2024-06-12T21:00:00Z", "EURUSD", "1.10"),
            Triple("2024-06-12T21:00:00Z", "BTCUSD", "60000"),
            Triple("2024-06-13T00:00:00Z", "BTCUSD", "60000"),
        )) {
            clock.time = ms(iso)
            broker.onTick(Tick(symbol, Money.of(price), clock.time))
        }

        assertThat(cancelled).containsExactly(
            "eur" to ms("2024-06-12T21:00:00Z"),
            "btc" to ms("2024-06-13T00:00:00Z"),
        )
    }
}
