package com.qkt.risk.rules

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.accounting.margin.MarginModel
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.EventContracts
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A YES contract priced 0.30: 100 YES need 30 of equity, 100 NO (a YES sell) need 70. */
class MarginRequirementEventTest {
    private val yes = EventContracts.SYMBOL
    private val registry = EventContracts.registry(EventContracts.meta())
    private val prices = MarketPriceTracker().apply { update(yes, BigDecimal("0.30")) }
    private var equity = BigDecimal("50")
    private val rule =
        MarginRequirement(
            MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
            prices,
        ) { equity }

    private class Book(
        private val held: Map<String, BigDecimal> = emptyMap(),
    ) : PositionProvider {
        override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal("0.30")) }

        override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal("0.30")) }

        override fun pendingOrderQuantity(
            symbol: String,
            side: Side,
            strategyId: String?,
        ): BigDecimal = BigDecimal.ZERO

        override fun pendingEntrySymbols(strategyId: String?) = emptySet<String>()
    }

    private fun market(
        side: Side,
        qty: String,
    ) = OrderRequest.Market("o", yes, side, BigDecimal(qty), TimeInForce.GTC, 0L, strategyId = "s")

    @Test
    fun `a YES buy needs its premium and passes within equity`() {
        assertThat(rule.evaluate(market(Side.BUY, "100"), Book())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a NO buy needs payout minus price per contract and is refused with the figures`() {
        val decision = rule.evaluate(market(Side.SELL, "100"), Book())

        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("70").contains("50")
    }

    @Test
    fun `selling out of a YES position is risk-reducing and never refused`() {
        equity = BigDecimal.ZERO

        assertThat(rule.evaluate(market(Side.SELL, "100"), Book(mapOf(yes to BigDecimal("100")))))
            .isEqualTo(Decision.Approve)
    }

    @Test
    fun `a flip from YES to NO is judged on the larger premium either side can leave`() {
        // Held 100 YES, selling 300 can leave 200 NO: 200 x 0.70 = 140, more than 100 YES x 0.30.
        equity = BigDecimal("139")

        assertThat(rule.evaluate(market(Side.SELL, "300"), Book(mapOf(yes to BigDecimal("100")))))
            .isInstanceOf(Decision.Reject::class.java)
    }
}
