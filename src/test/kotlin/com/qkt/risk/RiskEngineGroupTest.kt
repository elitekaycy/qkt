package com.qkt.risk

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
import com.qkt.risk.rules.MarginRequirement
import com.qkt.risk.rules.MaxOrderQty
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** P80000 mark 500, P78000 mark 300, C92000 mark 200 (2OCT26); legs of 0.1 contract. */
class RiskEngineGroupTest {
    private val oct2 = 1_790_928_000_000L
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
    private val registry =
        OptionCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    OptionCatalog(
                        root.root,
                        listOf(
                            OptionListing("BTC_USDC-2OCT26-80000-P", "80000", "put", oct2),
                            OptionListing("BTC_USDC-2OCT26-78000-P", "78000", "put", oct2),
                            OptionListing("BTC_USDC-2OCT26-92000-C", "92000", "call", oct2),
                        ),
                    ),
            ),
        )
    private val p80 = "DERIBIT:BTC_USDC_2OCT26_80000_P"
    private val p78 = "DERIBIT:BTC_USDC_2OCT26_78000_P"
    private val c92 = "DERIBIT:BTC_USDC_2OCT26_92000_C"
    private val prices =
        MarketPriceTracker().apply {
            update(p80, BigDecimal("500"))
            update(p78, BigDecimal("300"))
            update(c92, BigDecimal("200"))
        }
    private var equity = BigDecimal("180")
    private val flat =
        object : PositionProvider {
            override fun positionFor(symbol: String): Position? = null

            override fun allPositions() = emptyMap<String, Position>()
        }

    private fun engine(
        vararg extra: RiskRule,
        book: PositionProvider = flat,
    ) = RiskEngine(
        listOf(
            *extra,
            MarginRequirement(
                MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
                prices,
                OptionMargin(registry),
            ) {
                equity
            },
        ),
        book,
    )

    private fun leg(
        id: String,
        symbol: String,
        side: Side,
        qty: String = "0.1",
    ) = OrderRequest.Market(id, symbol, side, BigDecimal(qty), TimeInForce.GTC, 0L, "s1")

    @Test
    fun `a put credit spread is margined as one position though its short leg alone is not`() {
        val spread = listOf(leg("a", p80, Side.SELL), leg("b", p78, Side.BUY))

        assertThat(engine().approve(spread.first())).isInstanceOf(Decision.Reject::class.java)
        assertThat(engine().approveGroup(spread)).isEqualTo(Decision.Approve)
        equity = BigDecimal("179.99")
        assertThat(engine().approveGroup(spread)).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `an unbounded group is refused and per-order caps still apply to each leg`() {
        equity = BigDecimal("1000000")
        assertThat(
            (engine().approveGroup(listOf(leg("a", c92, Side.SELL))) as Decision.Reject).reason,
        ).contains("unbounded")
        val capped = engine(MaxOrderQty(BigDecimal("0.15")))
        assertThat(
            capped.approveGroup(listOf(leg("a", p80, Side.SELL), leg("b", p78, Side.BUY, "0.2"))),
        ).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `an unwind group closing a held spread passes even when equity covers nothing`() {
        val spread =
            object : PositionProvider {
                private val held = mapOf(p80 to BigDecimal("-0.1"), p78 to BigDecimal("0.1"))

                override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal.ONE) }

                override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal.ONE) }
            }
        equity = BigDecimal.ONE

        assertThat(
            engine(book = spread).approveGroup(listOf(leg("a", p80, Side.BUY), leg("b", p78, Side.SELL))),
        ).isEqualTo(Decision.Approve)
        assertThat(engine(book = spread).approve(leg("b", p78, Side.SELL))).isInstanceOf(Decision.Reject::class.java)
    }
}
