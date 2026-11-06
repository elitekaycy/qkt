package com.qkt.observe

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.events.Event
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditChainScopeTradedTest {
    private val call = "DERIBIT:BTC_USDC_31OCT26_80000_C"
    private val streams = listOf("DERIBIT:BTC_USDC_PERPETUAL", "OPTIONS:DERIBIT.BTC_USDC")

    private fun tick(symbol: String) = TickEvent(Tick(symbol = symbol, price = Money.of("1"), timestamp = 1L))

    private fun journalsQuotesAfter(event: Event): Boolean {
        val scope = AuditChainScope(streams) { false }
        assertThat(scope.journals(tick(call))).isFalse
        assertThat(scope.journals(event)).isTrue
        return scope.journals(tick(call))
    }

    @Test
    fun `a partial fill, reconciliation, trade or settlement starts journaling the contract`() {
        val one = Money.of("1")
        val events =
            listOf(
                BrokerEvent.OrderPartiallyFilled("o", "b", call, Side.BUY, one, one, one),
                BrokerEvent.PositionReconciled(call, null, one, null, one, "poll", "drift"),
                TradeEvent(Trade("o", call, one, one, Side.SELL, 1L)),
                ContractSettled(call, one),
            )
        events.forEach { assertThat(journalsQuotesAfter(it)).describedAs(it::class.simpleName).isTrue }
    }

    @Test
    fun `an event about no traded symbol leaves the contract unjournaled`() {
        assertThat(journalsQuotesAfter(SignalEvent(Signal.Buy(call, Money.of("1"))))).isFalse
    }
}
