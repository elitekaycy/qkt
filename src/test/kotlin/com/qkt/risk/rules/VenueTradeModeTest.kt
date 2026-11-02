package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.TradeMode
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Exness keeps symbols it no longer trades in its list; qkt refuses what the venue would refuse, before it. */
class VenueTradeModeTest {
    private val ledger = StrategyPositionTracker()
    private val positions = ledger.account

    private fun meta(
        symbol: String,
        mode: TradeMode,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal(100),
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = BigDecimal(10),
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        category = "Stocks",
        tradeMode = mode,
    )

    private val registry =
        object : InstrumentRegistry {
            private val table =
                listOf(
                    meta("EXNESS:AAPL", TradeMode.FULL),
                    meta("EXNESS:FB", TradeMode.CLOSE_ONLY),
                    meta("EXNESS:ATVI", TradeMode.DISABLED),
                    meta("EXNESS:LONGY", TradeMode.LONG_ONLY),
                    meta("EXNESS:SHORTY", TradeMode.SHORT_ONLY),
                ).associateBy { it.qktSymbol }

            override fun lookup(qktSymbol: String) = table[qktSymbol]
        }
    private val rule = VenueTradeMode(registry)

    private fun order(
        symbol: String,
        side: Side,
        qty: String = "0.01",
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = side,
        quantity = Money.of(qty),
        timeInForce = TimeInForce.GTC,
        timestamp = 1000L,
    )

    private fun hold(
        symbol: String,
        side: Side,
        qty: String,
    ) = ledger.apply("acct", Trade("ORD-X", symbol, Money.of("100"), Money.of(qty), side, 1000L))

    @Test
    fun `a fully tradable stock and a symbol with no entry are not judged`() {
        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.BUY), positions)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("EXNESS:AAPL", Side.SELL), positions)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("EXNESS:UNKNOWN", Side.BUY), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a close-only symbol refuses opening either way and lets an open position close`() {
        assertThat(rule.evaluate(order("EXNESS:FB", Side.BUY), positions))
            .isEqualTo(Decision.Reject("EXNESS:FB is close_only at the venue: opening BUY refused"))
        assertThat(rule.evaluate(order("EXNESS:FB", Side.SELL), positions)).isInstanceOf(Decision.Reject::class.java)
        hold("EXNESS:FB", Side.BUY, "0.05")
        assertThat(rule.evaluate(order("EXNESS:FB", Side.SELL, "0.05"), positions)).isEqualTo(Decision.Approve)
        // Selling more than the position would open a short: refused.
        assertThat(
            rule.evaluate(order("EXNESS:FB", Side.SELL, "0.06"), positions),
        ).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(order("EXNESS:FB", Side.BUY), positions)).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `a disabled symbol refuses everything, closing included`() {
        assertThat(rule.evaluate(order("EXNESS:ATVI", Side.BUY), positions)).isInstanceOf(Decision.Reject::class.java)
        hold("EXNESS:ATVI", Side.BUY, "0.02")
        assertThat(rule.evaluate(order("EXNESS:ATVI", Side.SELL, "0.02"), positions))
            .isEqualTo(Decision.Reject("EXNESS:ATVI is disabled at the venue: closing refused"))
    }

    @Test
    fun `long-only and short-only symbols open only on their side`() {
        assertThat(rule.evaluate(order("EXNESS:LONGY", Side.BUY), positions)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("EXNESS:LONGY", Side.SELL), positions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(order("EXNESS:SHORTY", Side.SELL), positions)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("EXNESS:SHORTY", Side.BUY), positions)).isInstanceOf(Decision.Reject::class.java)
    }
}
