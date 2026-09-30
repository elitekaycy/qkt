package com.qkt.research

import com.qkt.accounting.AccountingConfig
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PaperBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.continuous.ContinuousFixture
import com.qkt.broker.continuous.ContinuousFixture.Companion.ms
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayBrokerFuturesTest {
    private val registry = ContinuousFixture().registry
    private val clock = FixedClock(time = ms("2024-09-20T00:00:00Z"))
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = ReplayBooks(registry, AccountingConfig(), markTimestamp = { clock.time })
    private val front = "BINANCE_UM:BTCUSDT@front"
    private val dec = "BINANCE_UM:BTCUSDT_241227"
    private val gold = "EXNESS:XAUUSD"
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
    }

    private fun broker(
        symbols: List<String>,
        bus: EventBus = this.bus,
        books: ReplayBooks = this.books,
    ): Broker =
        replayBroker(
            ExecutionSimulationConfig(),
            bus,
            clock,
            books,
            false,
            TradingCalendar.crypto(),
            emptyMap(),
            symbols,
        )

    private fun tick(
        symbol: String,
        price: String,
        bus: EventBus = this.bus,
        books: ReplayBooks = this.books,
    ) {
        clock.time += 60_000L
        val tick = Tick(symbol, BigDecimal(price), clock.time)
        books.priceTracker.update(tick)
        bus.publish(TickEvent(tick))
    }

    private fun market(
        id: String,
        symbol: String,
        side: Side,
    ) = OrderRequest.Market(id, symbol, side, BigDecimal("0.01"), TimeInForce.GTC, clock.time, strategyId = "s")

    @Test
    fun `a run without futures gets today's broker`() {
        assertThat(broker(listOf(gold))).isInstanceOf(PaperBroker::class.java)
    }

    @Test
    fun `futures route to the exchange stack and every other symbol to today's broker`() {
        val routed = broker(listOf(gold, front, dec))

        val exchangeShapes =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.STOP,
                OrderTypeCapability.STOP_LIMIT,
            )
        assertThat(routed).isInstanceOf(CompositeBroker::class.java)
        assertThat(routed.capabilitiesFor(front)).isEqualTo(exchangeShapes)
        assertThat(routed.capabilitiesFor(dec)).isEqualTo(exchangeShapes)
        assertThat(routed.capabilitiesFor(gold)).contains(OrderTypeCapability.IF_TOUCHED)
        assertThat(routed.positionAccountingMode(front)).isEqualTo(PositionAccountingMode.NETTING)
        assertThat(routed.positionAccountingMode(dec)).isEqualTo(PositionAccountingMode.NETTING)
    }

    @Test
    fun `a listed contract trades on the engine's own ticks`() {
        val routed = broker(listOf(gold, dec))
        tick(dec, "63800.0")

        routed.submit(market("in", dec, Side.BUY))
        routed.submit(
            OrderRequest.Stop(
                "stop",
                dec,
                Side.SELL,
                BigDecimal("0.01"),
                BigDecimal("63700.0"),
                TimeInForce.GTC,
                clock.time,
                "s",
            ),
        )
        tick(dec, "63700.0")

        assertThat(fills.map { Triple(it.clientOrderId, it.symbol, it.price.toPlainString()) })
            .containsExactly(Triple("in", dec, "63800.00000000"), Triple("stop", dec, "63700.00000000"))
    }

    @Test
    fun `a continuous stream trades its contract and reports in the series`() {
        val routed = broker(listOf(front))
        tick(front, "63000.0")

        routed.submit(market("in", front, Side.BUY))

        assertThat(fills.single().symbol).isEqualTo(front)
        assertThat(fills.single().price).isEqualByComparingTo("63000.0")
    }

    @Test
    fun `the CFD side of a mixed run fills exactly as in a CFD-only run`() {
        fun cfdFills(symbols: List<String>): List<String> {
            clock.time = ms("2024-09-20T00:00:00Z")
            val runBus = EventBus(clock, MonotonicSequenceGenerator())
            val runBooks = ReplayBooks(registry, AccountingConfig(), markTimestamp = { clock.time })
            val seen = mutableListOf<String>()
            runBus.subscribe<BrokerEvent.OrderFilled> {
                seen +=
                    "${it.clientOrderId} ${it.side} ${it.price} ${it.timestamp}"
            }
            val routed = broker(symbols, runBus, runBooks)
            tick(gold, "2400.0", runBus, runBooks)
            routed.submit(market("g1", gold, Side.BUY))
            routed.submit(
                OrderRequest.Limit(
                    "g2",
                    gold,
                    Side.SELL,
                    BigDecimal("0.01"),
                    BigDecimal("2410.0"),
                    TimeInForce.GTC,
                    clock.time,
                    "s",
                ),
            )
            tick(gold, "2405.0", runBus, runBooks)
            tick(gold, "2411.0", runBus, runBooks)
            return seen
        }

        val alone = cfdFills(listOf(gold))

        assertThat(alone).hasSize(2)
        assertThat(cfdFills(listOf(gold, front, dec))).isEqualTo(alone)
    }
}
