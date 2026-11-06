package com.qkt.research

import com.qkt.accounting.AccountingConfig
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.PaperBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.events.TickEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.EventCatalogRegistry
import com.qkt.instrument.EventFamilyFixture
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.positions.LegRole
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** E1's 200 strike resolved YES; E2's contracts have no outcome yet. */
class ReplayEventRoutesTest {
    private val registry =
        LayeredInstrumentRegistry(
            listOf(
                EventCatalogRegistry(
                    listOf(EventFamilyFixture.FAMILY),
                    mapOf(EventFamilyFixture.ROOT to EventFamilyFixture.catalog(mapOf("KXT-E1-T2" to "1"))),
                ),
                StandardInstrumentRegistry,
            ),
        )
    private val clock = FixedClock(time = EventFamilyFixture.ms("2024-03-04T10:00:00Z"))
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = ReplayBooks(registry, AccountingConfig(), markTimestamp = { clock.time })
    private val yes = "KALSHI:KXT_E1_T2"
    private val open = "KALSHI:KXT_E2_T2"
    private val gold = "EXNESS:XAUUSD"
    private val seen = mutableListOf<Any>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> {
            seen += it
            books.strategyPositions.applyFill(it, LegIntent.Open("leg-${it.clientOrderId}", LegRole.PRIMARY))
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { seen += it }
        bus.subscribe<BrokerEvent.OrderRejected> { seen += it }
        bus.subscribe<ContractSettled> { seen += it }
    }

    private fun broker(symbols: List<String>): Broker =
        replayBroker(
            ExecutionSimulationConfig(),
            bus,
            clock,
            books,
            com.qkt.backtest.BarFills.NONE,
            TradingCalendar.crypto(),
            emptyMap(),
            symbols,
        )

    private fun quote(
        symbol: String,
        at: String,
        bid: String = "0.40",
        ask: String = "0.42",
    ) {
        clock.time = EventFamilyFixture.ms(at)
        val mid = BigDecimal(bid).add(BigDecimal(ask)).divide(BigDecimal(2))
        val tick = Tick(symbol, mid, clock.time, bid = BigDecimal(bid), ask = BigDecimal(ask))
        books.priceTracker.update(tick)
        bus.publish(TickEvent(tick))
    }

    private fun order(
        id: String,
        symbol: String,
        side: Side = Side.BUY,
        limit: String? = null,
    ): OrderRequest =
        if (limit == null) {
            OrderRequest.Market(id, symbol, side, BigDecimal.TEN, TimeInForce.GTC, clock.time, strategyId = "s")
        } else {
            OrderRequest.Limit(id, symbol, side, BigDecimal.TEN, BigDecimal(limit), TimeInForce.GTC, clock.time, "s")
        }

    private inline fun <reified T> events(): List<T> = seen.filterIsInstance<T>()

    @Test
    fun `a run without event contracts builds exactly the broker it built before`() {
        assertThat(broker(listOf(gold))).isInstanceOf(PaperBroker::class.java)
    }

    @Test
    fun `an event contract fills at the quoted ask on its own netting venue`() {
        val routed = broker(listOf(gold, yes))
        quote(yes, "2024-03-04T10:01:00Z")

        routed.submit(order("in", yes))
        quote(yes, "2024-03-04T10:02:00Z")

        assertThat(routed).isInstanceOf(CompositeBroker::class.java)
        assertThat(routed.positionAccountingMode(yes)).isEqualTo(PositionAccountingMode.NETTING)
        val fill = events<BrokerEvent.OrderFilled>().single()
        assertThat(fill.price).isEqualByComparingTo("0.42")
        assertThat(fill.quantity).isEqualByComparingTo("10")
    }

    @Test
    fun `an order at or after the contract's close is refused`() {
        val routed = broker(listOf(yes))
        quote(open, "2024-03-04T12:00:00Z")

        val ack = routed.submit(order("late", yes))

        assertThat(ack.accepted).isFalse()
        assertThat(events<BrokerEvent.OrderRejected>().single().reason).contains("stopped trading")
    }

    @Test
    fun `at expiry working orders are cancelled and holders settle at the outcome`() {
        val routed = broker(listOf(yes, open))
        quote(yes, "2024-03-04T10:01:00Z")
        routed.submit(order("in", yes))
        routed.submit(order("rest", yes, limit = "0.10"))
        quote(yes, "2024-03-04T11:59:00Z", bid = "0.90", ask = "0.92")
        assertThat(events<ContractSettled>()).isEmpty()

        quote(open, "2024-03-04T12:00:30Z")

        assertThat(events<BrokerEvent.OrderCancelled>().single().clientOrderId).isEqualTo("rest")
        val settled = events<ContractSettled>().single()
        assertThat(settled.symbol).isEqualTo(yes)
        assertThat(settled.price).isEqualByComparingTo("1")
        assertThat(settled.timestamp).isEqualTo(EventFamilyFixture.ms("2024-03-04T12:00:30Z"))
        val record = books.settlements.entries.single()
        assertThat(record.side).isEqualTo(Side.SELL)
        assertThat(record.quantity).isEqualByComparingTo("10")
        assertThat(record.price).isEqualByComparingTo("1")
        quote(open, "2024-03-04T12:01:00Z")
        assertThat(events<ContractSettled>()).hasSize(1)
    }

    @Test
    fun `a contract expiring unheld only lapses its orders`() {
        val routed = broker(listOf(yes))
        quote(yes, "2024-03-04T10:01:00Z")
        routed.submit(order("rest", yes, limit = "0.10"))

        quote(open, "2024-03-04T12:00:00Z")

        assertThat(events<BrokerEvent.OrderCancelled>().single().clientOrderId).isEqualTo("rest")
        assertThat(events<ContractSettled>()).isEmpty()
        assertThat(books.settlements.entries).isEmpty()
    }

    @Test
    fun `a held contract that expired without an outcome fails naming it`() {
        val routed = broker(listOf(open))
        quote(open, "2024-03-04T10:01:00Z")
        routed.submit(order("in", open))
        quote(open, "2024-03-04T10:02:00Z")

        assertThatThrownBy { quote(open, "2024-03-04T18:00:00Z") }
            .hasMessageContaining(open)
            .hasMessageContaining("no outcome")
            .hasMessageContaining("--kind events")
    }
}
