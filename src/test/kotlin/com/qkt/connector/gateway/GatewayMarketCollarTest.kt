package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A market entry on a thin contract never fills beyond its collar, and an exit is never held by one. Paper venue on Deribit's
 * tickers, 2026-11-02: 2 of 7 market entries filled at 38,937.5 with the market near 77,400.
 */
class GatewayMarketCollarTest {
    private val symbol = "D:BTC_USDC_4NOV26"
    private val clock = FixedClock(time = 1_000L)
    private val prices = MarketPriceTracker().also { it.update(Tick(symbol, BigDecimal("77400"), 1L)) }
    private val sent = mutableListOf<OrderRequest>()
    private val events = mutableListOf<BrokerEvent>()
    private val delays = mutableListOf<Long>()
    private val scheduled = ArrayDeque<() -> Unit>()
    private val collar =
        GatewayMarketCollar(
            BigDecimal("0.25"),
            prices,
            clock,
            collars = { it == symbol },
            exits = { it.legIntent is LegIntent.Close || it.closesTicket != null },
            send = { sent += it },
            later = { delay, action ->
                delays += delay
                scheduled += action
            },
            publish = { events += it },
        )

    private fun market(
        side: Side = Side.SELL,
        closes: String? = null,
        on: String = symbol,
    ) = OrderRequest.Market("o", on, side, BigDecimal("1.0"), TimeInForce.GTC, 900L, "s", closesTicket = closes)

    private fun start(request: OrderRequest) = collar.first(request).also { sent += it }

    private fun venueCancels() =
        collar.heard(BrokerEvent.OrderCancelled("o", "v", "cancelled at the venue", "s", clock.time))

    private fun filled(price: String) =
        BrokerEvent.OrderFilled("o", "v", symbol, Side.SELL, BigDecimal(price), BigDecimal("1.0"), "s", 1L)

    /** A slice as the gateway reports it: its total is of this attempt alone. */
    private fun sliceOf(quantity: String) =
        BigDecimal(quantity).let {
            BrokerEvent.OrderPartiallyFilled("o", "v", symbol, Side.SELL, BigDecimal("77300"), it, it, "s", 1L)
        }

    private fun retry() = scheduled.removeFirst()()

    @Test
    fun `a sell goes as an ioc limit below the mark and a buy above, whatever the book says`() {
        val sell = start(market(Side.SELL)) as OrderRequest.Limit
        assertThat(sell.limitPrice).isEqualByComparingTo("58050")
        assertThat(sell.timeInForce).isEqualTo(TimeInForce.IOC)

        collar.heard(filled("77300"))

        val buy = start(market(Side.BUY)) as OrderRequest.Limit
        assertThat(buy.limitPrice).isEqualByComparingTo("96750")
    }

    @Test
    fun `other contracts and order types pass untouched`() {
        val other = market(on = "D:BTC-PERPETUAL")
        assertThat(collar.first(other)).isSameAs(other)
        val limit = OrderRequest.Limit("l", symbol, Side.BUY, BigDecimal.ONE, BigDecimal.ONE, TimeInForce.GTC, 1L, "s")
        assertThat(collar.first(limit)).isSameAs(limit)
    }

    @Test
    fun `a venue cancel is retried around the reference as it then stands, under a new gateway id`() {
        val first = start(market())
        venueCancels()
        assertThat(events).isEmpty()
        prices.update(Tick(symbol, BigDecimal("80000"), 2L))
        retry()

        val second = sent.last() as OrderRequest.Limit
        assertThat(second.limitPrice).isEqualByComparingTo("60000")
        assertThat(GatewayClientIds.of(second)).isNotEqualTo(GatewayClientIds.of(first))
        assertThat(GatewayClientIds.engineId(GatewayClientIds.of(second))).isEqualTo("o")
    }

    @Test
    fun `an entry is rejected with a plain reason after its attempts, on growing gaps`() {
        start(market())
        repeat(GatewayMarketCollar.MAX_ATTEMPTS - 1) {
            venueCancels()
            retry()
        }
        venueCancels()

        assertThat(sent).hasSize(GatewayMarketCollar.MAX_ATTEMPTS)
        assertThat(sent).allMatch { it is OrderRequest.Limit }
        assertThat(delays).containsExactly(1_000L, 2_000L)
        val rejected = events.single() as BrokerEvent.OrderRejected
        assertThat(rejected.clientOrderId).isEqualTo("o")
        assertThat(rejected.reason).contains("not filled").contains("0.25").contains("reference 77400")
    }

    @Test
    fun `an exit goes as a plain market order at once, so it is never stuck`() {
        val exit = market(closes = "t-1")

        assertThat(collar.refusal(exit)).isNull()
        assertThat(collar.first(exit)).isSameAs(exit)

        collar.heard(filled("38937.5"))
        assertThat(events.single()).isInstanceOf(BrokerEvent.OrderFilled::class.java)
    }

    @Test
    fun `no reference refuses an entry and lets an exit go plain`() {
        val blind = "D:BTC_USDC_5NOV26"
        val always =
            GatewayMarketCollar(
                BigDecimal("0.25"),
                MarketPriceTracker(),
                clock,
                collars = { true },
                exits = { it.closesTicket != null },
                send = {},
                later = { _, _ -> },
                publish = {},
            )

        assertThat(always.refusal(market(on = blind))).contains("no price heard")
        val exit = market(closes = "t-1", on = blind)
        assertThat(always.refusal(exit)).isNull()
        assertThat(always.first(exit)).isSameAs(exit)
    }

    @Test
    fun `a part fill carries over, the retry asks for the rest, and the engine hears one running total`() {
        start(market())
        collar.heard(sliceOf("0.4"))
        venueCancels()
        retry()
        assertThat(sent.last().quantity).isEqualByComparingTo("0.6")
        collar.heard(sliceOf("0.2"))

        val partials = events.filterIsInstance<BrokerEvent.OrderPartiallyFilled>()
        assertThat(partials.last().cumulativeFilled).isEqualByComparingTo("0.6")
        venueCancels()
        retry()
        venueCancels()
        // Part of an entry filled: it ends cancelled, keeping what it has, with the reason.
        assertThat((events.last() as BrokerEvent.OrderCancelled).reason).contains("not filled")
    }

    @Test
    fun `only the first accept reaches the engine`() {
        start(market())
        collar.heard(BrokerEvent.OrderAccepted("o", "v", "s", 1L))
        venueCancels()
        retry()
        collar.heard(BrokerEvent.OrderAccepted("o", "v2", "s", 1L))

        assertThat(events.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)
    }

    @Test
    fun `an engine cancel between attempts ends it, and no retry goes`() {
        start(market())
        venueCancels()

        assertThat(collar.cancelled("o")).isTrue
        retry()

        assertThat(sent).hasSize(1)
        assertThat(events.single()).isInstanceOf(BrokerEvent.OrderCancelled::class.java)
    }

    @Test
    fun `an engine cancel with an attempt out waits for the venue's answer, and is not retried`() {
        start(market())
        assertThat(collar.cancelled("o")).isFalse
        venueCancels()

        assertThat(scheduled).isEmpty()
        assertThat(events.single()).isInstanceOf(BrokerEvent.OrderCancelled::class.java)
    }

    @Test
    fun `a venue refusing the limit gets a plain market order, and the kill switch is final`() {
        start(market())
        assertThat(collar.refused("o", "venue_rejected: price beyond the limit")).isTrue
        assertThat(sent.last()).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(collar.refused("o", "venue_rejected: no margin")).isFalse

        start(market())
        assertThat(collar.refused("o", "kill_switch: halted")).isFalse
        assertThat(sent.last()).isInstanceOf(OrderRequest.Limit::class.java)
    }
}
