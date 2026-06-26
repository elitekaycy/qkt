package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PreTradeControlsTest {
    private val positions = PositionTracker()

    private fun market(qty: String) =
        OrderRequest.Market(
            id = "m1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of(qty),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    private fun market(
        symbol: String,
        qty: String,
        timestamp: Long = 0L,
    ) = OrderRequest.Market(
        id = "m1",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of(qty),
        timeInForce = TimeInForce.GTC,
        timestamp = timestamp,
    )

    private fun limit(
        qty: String,
        price: String,
    ) = OrderRequest.Limit(
        id = "l1",
        symbol = "X",
        side = Side.BUY,
        quantity = Money.of(qty),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `qty cap rejects an oversized order with the cap in the reason`() {
        val rule = MaxOrderQty(BigDecimal("10"))
        val decision = rule.evaluate(market("11"), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("per-order cap 10")
        assertThat(rule.evaluate(market("10"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `notional cap rejects on qty times price times contractSize`() {
        val prices = MarketPriceTracker().apply { update("X", Money.of("2000")) }
        val rule = MaxOrderNotional(BigDecimal("100000"), prices)
        // 60 x 2000 = 120,000 > 100,000.
        val decision = rule.evaluate(market("60"), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("exceeds cap")
        // 40 x 2000 = 80,000 passes.
        assertThat(rule.evaluate(market("40"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `notional cap compares account-currency notional for FX symbols`() {
        val prices = MarketPriceTracker().apply { update("BACKTEST:USDJPY", Money.of("150")) }
        val rule = MaxOrderNotional(BigDecimal("100000"), prices)

        assertThat(rule.evaluate(market("BACKTEST:USDJPY", "100000"), positions)).isEqualTo(Decision.Approve)
        val decision = rule.evaluate(market("BACKTEST:USDJPY", "100001"), positions)

        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("currency=USD")
    }

    @Test
    fun `notional cap rejects recognized non-account symbols without conversion`() {
        val prices = MarketPriceTracker().apply { update("BACKTEST:EURJPY", Money.of("160")) }
        val rule = MaxOrderNotional(BigDecimal("100000"), prices)

        val decision = rule.evaluate(market("BACKTEST:EURJPY", "1000"), positions)

        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("missing FX conversion JPY->USD")
    }

    @Test
    fun `unpriceable order is rejected, not silently passed`() {
        val rule = MaxOrderNotional(BigDecimal("100000"), MarketPriceTracker())
        val decision = rule.evaluate(market("1"), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("no price reference")
    }

    @Test
    fun `price collar rejects a stale-priced limit`() {
        val prices = MarketPriceTracker().apply { update("X", Money.of("100")) }
        val rule = PriceCollar(BigDecimal("0.10"), prices)
        // 115 is 15% from the last price 100 — outside the 10% collar.
        val decision = rule.evaluate(limit("1", "115"), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("collar")
        // 105 is within the band.
        assertThat(rule.evaluate(limit("1", "105"), positions)).isEqualTo(Decision.Approve)
        // Market orders carry no explicit price and pass.
        assertThat(rule.evaluate(market("1"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `standard set ships all three controls`() {
        val rules = PreTradeControls.standard(MarketPriceTracker())
        assertThat(rules).hasSize(3)
    }
}
