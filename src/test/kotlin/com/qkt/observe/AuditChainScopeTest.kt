package com.qkt.observe

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditChainScopeTest {
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val call = "DERIBIT:BTC_USDC_31OCT26_80000_C"
    private val put = "DERIBIT:BTC_USDC_29OCT26_74000_P"
    private val streams = listOf(perp, "OPTIONS:DERIBIT.BTC_USDC")
    private val held = mutableSetOf<String>()
    private val scope = AuditChainScope(streams) { it in held }

    private fun tick(symbol: String) = TickEvent(Tick(symbol = symbol, price = Money.of("1"), timestamp = 1L))

    private fun candle(symbol: String) =
        CandleEvent(
            Candle(symbol, Money.of("1"), Money.of("1"), Money.of("1"), Money.of("1"), Money.of("1"), 0L, 60_000L),
        )

    private fun order(symbol: String) =
        OrderEvent(
            OrderRequest.Market(
                id = "o-1",
                symbol = symbol,
                side = Side.BUY,
                quantity = Money.of("0.01"),
                timeInForce = TimeInForce.GTC,
                strategyId = "alpha",
                timestamp = 1L,
            ),
        )

    @Test
    fun `quotes and candles of a chain contract the strategy never traded are not journaled`() {
        assertThat(scope.journals(tick(call))).isFalse()
        assertThat(scope.journals(candle(call))).isFalse()
        assertThat(scope.journals(tick(put))).isFalse()
    }

    @Test
    fun `a declared stream and every decision event are always journaled`() {
        assertThat(scope.journals(tick(perp))).isTrue()
        assertThat(scope.journals(candle(perp))).isTrue()
        assertThat(scope.journals(order(call))).isTrue()
    }

    @Test
    fun `a chain contract's quotes are journaled from the first order on it`() {
        assertThat(scope.journals(tick(call))).isFalse()

        scope.journals(order(call))

        assertThat(scope.journals(tick(call))).isTrue()
        assertThat(scope.journals(candle(call))).isTrue()
        assertThat(scope.journals(tick(put))).`as`("another contract stays omitted").isFalse()
    }

    @Test
    fun `a filled or held chain contract is journaled, so a position restored at restart keeps its marks`() {
        scope.journals(
            BrokerEvent.OrderFilled(
                clientOrderId = "o-9",
                brokerOrderId = "b-9",
                symbol = put,
                side = Side.SELL,
                price = Money.of("20"),
                quantity = Money.of("0.01"),
            ),
        )
        held += call

        assertThat(scope.journals(tick(put))).isTrue()
        assertThat(scope.journals(tick(call))).isTrue()
    }

    @Test
    fun `a session without an option root feed journals every quote`() {
        val plain = AuditChainScope(listOf(perp, call)) { false }

        assertThat(plain.journals(tick(call))).isTrue()
        assertThat(plain.journals(tick(put))).isTrue()
    }

    @Test
    fun `a chain contract also declared as its own stream is journaled`() {
        val declared = AuditChainScope(streams + call) { false }

        assertThat(declared.journals(tick(call))).isTrue()
        assertThat(declared.journals(tick(put))).isFalse()
    }
}
