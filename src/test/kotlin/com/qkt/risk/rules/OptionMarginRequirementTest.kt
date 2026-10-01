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

/** Marks: P80000 500, P78000 300, C92000 200, C94000 100 (2OCT26); C94000 9OCT26 300. Sizes 0.1 contract. */
class OptionMarginRequirementTest {
    private val oct2 = 1_790_928_000_000L
    private val oct9 = oct2 + 7 * 86_400_000L
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
    private val listings =
        listOf(
            OptionListing("BTC_USDC-2OCT26-80000-P", "80000", "put", oct2),
            OptionListing("BTC_USDC-2OCT26-78000-P", "78000", "put", oct2),
            OptionListing("BTC_USDC-2OCT26-92000-C", "92000", "call", oct2),
            OptionListing("BTC_USDC-9OCT26-94000-C", "94000", "call", oct9),
            OptionListing("BTC_USDC-2OCT26-94000-C", "94000", "call", oct2),
        )
    private val registry = OptionCatalogRegistry(listOf(root), mapOf(root.root to OptionCatalog(root.root, listings)))
    private val p80 = "DERIBIT:BTC_USDC_2OCT26_80000_P"
    private val p78 = "DERIBIT:BTC_USDC_2OCT26_78000_P"
    private val c92 = "DERIBIT:BTC_USDC_2OCT26_92000_C"
    private val c94later = "DERIBIT:BTC_USDC_9OCT26_94000_C"
    private val c94 = "DERIBIT:BTC_USDC_2OCT26_94000_C"
    private val prices =
        MarketPriceTracker().apply {
            update(p80, BigDecimal("500"))
            update(p78, BigDecimal("300"))
            update(c92, BigDecimal("200"))
            update(c94later, BigDecimal("300"))
            update(c94, BigDecimal("100"))
        }
    private var equity = BigDecimal("100000")
    private val rule =
        MarginRequirement(
            MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
            prices,
            options = OptionMargin(registry),
        ) { equity }

    private class Book(
        private val held: Map<String, BigDecimal> = emptyMap(),
        private val pendingSells: Map<String, BigDecimal> = emptyMap(),
        private val pendingBuys: Map<String, BigDecimal> = emptyMap(),
    ) : PositionProvider {
        override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal.ONE) }

        override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal.ONE) }

        override fun pendingOrderQuantity(
            symbol: String,
            side: Side,
            strategyId: String?,
        ): BigDecimal = (if (side == Side.SELL) pendingSells[symbol] else pendingBuys[symbol]) ?: BigDecimal.ZERO

        override fun pendingEntrySymbols(strategyId: String?) = pendingSells.keys + pendingBuys.keys
    }

    private fun order(
        symbol: String,
        side: Side,
        limit: String? = null,
    ): OrderRequest =
        if (limit == null) {
            OrderRequest.Market("o", symbol, side, BigDecimal("0.1"), TimeInForce.GTC, 0L, "s1")
        } else {
            OrderRequest.Limit("o", symbol, side, BigDecimal("0.1"), BigDecimal(limit), TimeInForce.GTC, 0L, "s1")
        }

    private fun decide(
        symbol: String,
        side: Side,
        book: Book = Book(),
        withEquity: String,
    ): Decision {
        equity = BigDecimal(withEquity)
        return rule.evaluate(order(symbol, side), book)
    }

    @Test
    fun `a long option needs its premium`() {
        assertThat(decide(c92, Side.BUY, withEquity = "19.99")).isInstanceOf(Decision.Reject::class.java)
        assertThat(decide(c92, Side.BUY, withEquity = "20")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a short put is secured by its strike less its mark`() {
        // value -50, worst payoff at S = 0: -8000; 7950 needed.
        assertThat(decide(p80, Side.SELL, withEquity = "7949.99")).isInstanceOf(Decision.Reject::class.java)
        assertThat(decide(p80, Side.SELL, withEquity = "7950")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a put credit spread needs its width less its credit`() {
        val longWing = Book(held = mapOf(p78 to BigDecimal("0.1")))
        // value 30 - 50 = -20, worst payoff -8000 + 7800 = -200; 180 needed.
        assertThat(decide(p80, Side.SELL, longWing, withEquity = "179.99")).isInstanceOf(Decision.Reject::class.java)
        assertThat(decide(p80, Side.SELL, longWing, withEquity = "180")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a naked short call, or one covered only by a later expiry, is refused as unbounded`() {
        val later = Book(held = mapOf(c94later to BigDecimal("0.1")))

        assertThat((decide(c92, Side.SELL, withEquity = "1000000") as Decision.Reject).reason).contains("unbounded")
        assertThat(
            (decide(c92, Side.SELL, later, withEquity = "1000000") as Decision.Reject).reason,
        ).contains("unbounded")
    }

    @Test
    fun `buying back a short always passes`() {
        val short = Book(held = mapOf(p80 to BigDecimal("-0.1")))

        assertThat(decide(p80, Side.BUY, short, withEquity = "0")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `pending sells count against equity as if they filled`() {
        val pending = Book(pendingSells = mapOf(p80 to BigDecimal("0.1")))

        assertThat(decide(p80, Side.SELL, pending, withEquity = "15899.99")).isInstanceOf(Decision.Reject::class.java)
        assertThat(decide(p80, Side.SELL, pending, withEquity = "15900")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `selling the long wing of a call credit spread would leave a naked short call and is refused`() {
        val spread = Book(held = mapOf(c92 to BigDecimal("-0.1"), c94 to BigDecimal("0.1")))

        assertThat(
            (decide(c94, Side.SELL, spread, withEquity = "1000000") as Decision.Reject).reason,
        ).contains("unbounded")
    }

    @Test
    fun `an order that lowers the worst case passes even when equity no longer covers it`() {
        // Short put: 7950 needed; buying back half halves it, though equity covers neither.
        val short = Book(held = mapOf(p80 to BigDecimal("-0.2")))

        assertThat(decide(p80, Side.BUY, short, withEquity = "100")).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a sale stacked on a pending sale of the same long would leave a naked call and is refused`() {
        val closing = Book(held = mapOf(c92 to BigDecimal("0.1")), pendingSells = mapOf(c92 to BigDecimal("0.1")))

        assertThat(
            (decide(c92, Side.SELL, closing, withEquity = "1000000") as Decision.Reject).reason,
        ).contains("unbounded")
    }

    @Test
    fun `a limit price does not revalue the position already held`() {
        // Held -1.0 P80 needs 79500 at its 500 mark; a 0.1 sell limit at 79000 must not shrink that.
        equity = BigDecimal("2000")
        val decision =
            rule.evaluate(
                order(p80, Side.SELL, limit = "79000"),
                Book(held = mapOf(p80 to BigDecimal("-1.0"))),
            )

        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `the worst case looks at every mix of pending orders filling or not`() {
        // Held +0.1 C92 with pending sells of C92 (a close) and P80 (a new short): buying P78 needs 200 when
        // the P80 sale fills and the C92 close does not.
        val book =
            Book(
                held = mapOf(c92 to BigDecimal("0.1")),
                pendingSells =
                    mapOf(
                        c92 to BigDecimal("0.1"),
                        p80 to BigDecimal("0.1"),
                    ),
            )

        val outcome = OptionMargin(registry).required(p78, Side.BUY, BigDecimal("0.1"), book) { prices.lastPrice(it) }

        assertThat((outcome as OptionMargin.Outcome.Required).amount).isEqualByComparingTo("200")
    }
}
