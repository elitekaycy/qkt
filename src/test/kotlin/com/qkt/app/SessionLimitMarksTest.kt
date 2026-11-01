package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.IntentBook
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Which marks the daemon's hard limit may count. A flatten must never rest on one of the others. */
class SessionLimitMarksTest {
    private val clock = FixedClock(1_800_000_000_000L)
    private val positions = StrategyPositionTracker()
    private val prices = MarketPriceTracker()
    private val gate = MarketDataGate(clock)
    private val marks = SessionLimitMarks(positions, StrategyPnL(positions, prices), prices, gate)

    private fun long(symbol: String) {
        val intents = IntentBook()
        intents.independentOpen("s", "c-$symbol", "leg-$symbol")
        intents.apply(
            positions,
            BrokerEvent.OrderFilled(
                clientOrderId = "c-$symbol",
                brokerOrderId = "b",
                symbol = symbol,
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("1"),
                strategyId = "s",
                timestamp = 0L,
            ),
        )
    }

    private fun live(
        symbol: String,
        price: String,
    ) {
        gate.observe(Tick(symbol, BigDecimal(price), clock.now()))
        prices.update(symbol, BigDecimal(price))
    }

    private fun read() = marks.read(listOf("s"), BigDecimal("-3"), StopFlatten.Book(emptyList(), false))

    @Test
    fun `a fresh live price counts in full`() {
        long("A")
        live("A", "80")
        val r = read()
        assertThat(r.open).isEqualByComparingTo("-20")
        assertThat(r.unmeasured).isEmpty()
        assertThat(r.realizedToday).isEqualByComparingTo("-3")
    }

    @Test
    fun `a stale quote counts as nothing, only for its symbol`() {
        long("A")
        long("B")
        live("A", "80")
        clock.time += 120_000L
        live("B", "90")
        val r = read()
        assertThat(r.open).isEqualByComparingTo("-10")
        assertThat(r.unmeasured).containsOnlyKeys("A")
    }

    @Test
    fun `a price of zero counts as nothing`() {
        long("A")
        live("A", "100")
        prices.update("A", BigDecimal.ZERO)
        val r = read()
        assertThat(r.open).isEqualByComparingTo("0")
        assertThat(r.unmeasured["A"]).contains("invalid price")
    }

    @Test
    fun `a warmup or restored price with no live tick since the start counts as nothing`() {
        long("A")
        prices.update("A", BigDecimal("1"))
        val r = read()
        assertThat(r.open).isEqualByComparingTo("0")
        assertThat(r.unmeasured["A"]).contains("no live tick")
    }

    @Test
    fun `no price at all counts as nothing`() {
        long("A")
        assertThat(read().unmeasured["A"]).isEqualTo("no price")
    }
}
