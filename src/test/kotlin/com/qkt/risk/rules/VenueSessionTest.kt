package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.PerSymbolCalendar
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A venue refuses every order outside a symbol's trading session (Exness quotes US stock CFDs from 06:00 New York but
 * answers MT5 retcode 10044 "session closed" until the regular open); qkt refuses them first, closes included.
 */
class VenueSessionTest {
    private val ledger = StrategyPositionTracker()
    private val positions = ledger.account
    private val calendars =
        mapOf(
            "EXNESS:AAPL" to requireNotNull(TradingCalendar.named("nyse[09:30-15:45]")),
            "EXNESS:BTCUSD" to TradingCalendar.crypto(),
        )
    private val calendar = PerSymbolCalendar(TradingCalendar.crypto(), calendars.keys.toList(), calendars::getValue)
    private val rule = VenueSession { symbol, atMs -> calendar.isInSession(symbol, Instant.ofEpochMilli(atMs)) }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun order(
        symbol: String,
        side: Side,
        at: String,
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = side,
        quantity = Money.of("1"),
        timeInForce = TimeInForce.GTC,
        timestamp = ms(at),
    )

    @Test
    fun `a stock order before the regular open is refused and one inside the session passes`() {
        // 2026-10-28 is a Wednesday; New York is on daylight time (UTC-4), so 07:00 NY is 11:00 UTC.
        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.BUY, "2026-10-28T11:00:00Z"), positions))
            .isEqualTo(
                Decision.Reject(
                    "EXNESS:AAPL is outside its trading session at 2026-10-28T11:00:00Z: the venue takes no order " +
                        "until it opens",
                ),
            )
        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.BUY, "2026-10-28T13:30:00Z"), positions))
            .isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.BUY, "2026-10-28T19:45:00Z"), positions))
            .isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `a close outside the session is refused too, as the venue refuses it`() {
        ledger.apply("acct", Trade("ORD-X", "EXNESS:AAPL", Money.of("200"), Money.of("1"), Side.BUY, 1000L))

        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.SELL, "2026-10-28T11:00:00Z"), positions))
            .isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.SELL, "2026-10-28T14:00:00Z"), positions))
            .isEqualTo(Decision.Approve)
    }

    @Test
    fun `a stock on a weekend is refused while a symbol on the crypto calendar trades`() {
        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.BUY, "2026-10-31T15:00:00Z"), positions))
            .isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(order("EXNESS:BTCUSD", Side.BUY, "2026-10-31T15:00:00Z"), positions))
            .isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("EXNESS:BTCUSD", Side.SELL, "2026-10-28T11:00:00Z"), positions))
            .isEqualTo(Decision.Approve)
    }

    @Test
    fun `the standard controls carry the rule only when a session is given`() {
        val prices = com.qkt.marketdata.MarketPriceTracker()
        assertThat(PreTradeControls.standard(prices).none { it is VenueSession }).isTrue()
        assertThat(PreTradeControls.standard(prices, session = { _, _ -> true }).count { it is VenueSession })
            .isEqualTo(1)
    }
}
