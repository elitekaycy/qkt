package com.qkt.risk.rules

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.accounting.margin.MarginModel
import com.qkt.accounting.margin.OptionMargin
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import com.qkt.instrument.TickSteps
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An option with no last price is marked at the held position's entry, else at the order's own price. */
class OptionMarginMarkTest {
    private val root =
        OptionRoot(
            "DERIBIT:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5")),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
            chains = QuoteSource.BOOK,
        )
    private val listing = OptionListing("BTC_USDC-2OCT26-92000-C", "92000", "call", 1_790_928_000_000L)
    private val registry =
        OptionCatalogRegistry(listOf(root), mapOf(root.root to OptionCatalog(root.root, listOf(listing))))
    private val c92 = "DERIBIT:BTC_USDC_2OCT26_92000_C"
    private val prices = MarketPriceTracker()
    private var equity = BigDecimal.ZERO
    private val rule =
        MarginRequirement(
            MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
            prices,
            options = OptionMargin(registry),
        ) { equity }

    private class Book(
        private val held: Map<String, Position> = emptyMap(),
    ) : PositionProvider {
        override fun positionFor(symbol: String) = held[symbol]

        override fun allPositions() = held
    }

    private fun buyLimit(price: String) =
        OrderRequest.Limit("o", c92, Side.BUY, BigDecimal("0.1"), BigDecimal(price), TimeInForce.GTC, 0L, "s1")

    @Test
    fun `an unquoted option order is valued at its own limit price`() {
        // 0.1 at 150 costs a premium of 15.
        equity = BigDecimal("14.99")
        assertThat(rule.evaluate(buyLimit("150"), Book())).isInstanceOf(Decision.Reject::class.java)

        equity = BigDecimal("15")
        assertThat(rule.evaluate(buyLimit("150"), Book())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `an unquoted option already held is valued at its entry, not the order's price`() {
        val held = Book(mapOf(c92 to Position(c92, BigDecimal("0.1"), BigDecimal("100"))))

        // 0.2 held after the fill, at its 100 entry: 20.
        equity = BigDecimal("19.99")
        assertThat(rule.evaluate(buyLimit("150"), held)).isInstanceOf(Decision.Reject::class.java)

        equity = BigDecimal("20")
        assertThat(rule.evaluate(buyLimit("150"), held)).isEqualTo(Decision.Approve)
    }
}
