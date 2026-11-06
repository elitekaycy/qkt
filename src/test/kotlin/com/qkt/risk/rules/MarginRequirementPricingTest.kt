package com.qkt.risk.rules

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.accounting.margin.MarginModel
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.MarginBasis
import com.qkt.instrument.MarginTerms
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** BTCUSDT quarterly at 5% initial margin on notional; no market price unless a test sets one. */
class MarginRequirementPricingTest {
    private val dec = "BINANCE_UM:BTCUSDT_241227"
    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            MarginTerms(BigDecimal("0.05"), BigDecimal("0.025"), MarginBasis.NOTIONAL),
        )
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to ContractCatalog(root.root, listOf(ListedContract("BTCUSDT_241227", 1_735_286_400_000L))),
            ),
        )
    private val prices = MarketPriceTracker()
    private var equity = BigDecimal("1000")
    private val rule =
        MarginRequirement(
            MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
            prices,
        ) { equity }

    private class Held(
        private val qty: BigDecimal?,
        private val entry: String = "64000",
    ) : PositionProvider {
        override fun positionFor(symbol: String) = qty?.let { Position(symbol, it, BigDecimal(entry)) }

        override fun allPositions(): Map<String, Position> =
            positionFor("BINANCE_UM:BTCUSDT_241227")?.let { mapOf(it.symbol to it) } ?: emptyMap()
    }

    private fun market(qty: String) = OrderRequest.Market("o", dec, Side.BUY, BigDecimal(qty), TimeInForce.GTC, 0L, "s")

    private fun limit(
        qty: String,
        price: String,
    ) = OrderRequest.Limit("l", dec, Side.BUY, BigDecimal(qty), BigDecimal(price), TimeInForce.GTC, 0L, "s")

    @Test
    fun `a margined symbol with no price anywhere is refused as unpriceable`() {
        val decision = rule.evaluate(market("0.1"), Held(null))

        assertThat((decision as Decision.Reject).reason)
            .isEqualTo("cannot compute margin for $dec: no price reference")
    }

    @Test
    fun `without a market price a held position is valued at its entry`() {
        equity = BigDecimal("2559")
        assertThat(rule.evaluate(market("0.3"), Held(BigDecimal("0.5")))).isInstanceOf(Decision.Reject::class.java)

        equity = BigDecimal("2560")
        assertThat(rule.evaluate(market("0.3"), Held(BigDecimal("0.5")))).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a priced order is margined at its own price whatever its kind`() {
        // 0.5 at 32000 needs 800 against 1000 of equity.
        val qty = BigDecimal("0.5")
        val at = BigDecimal("32000")
        val orders =
            listOf(
                limit("0.5", "32000"),
                OrderRequest.Stop("s", dec, Side.BUY, qty, at, TimeInForce.GTC, 0L, "s"),
                OrderRequest.StopLimit("sl", dec, Side.BUY, qty, at, at, TimeInForce.GTC, 0L, "s"),
                OrderRequest.IfTouched(
                    "it",
                    dec,
                    Side.BUY,
                    qty,
                    at,
                    TriggerType.MARKET,
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
                OrderRequest.Bracket(
                    "b",
                    dec,
                    Side.BUY,
                    qty,
                    limit("0.5", "32000"),
                    BigDecimal("40000"),
                    StopLossSpec.Fixed(BigDecimal("30000")),
                    TimeInForce.GTC,
                    0L,
                ),
            )

        assertThat(orders.map { rule.evaluate(it, Held(null)) }).containsOnly(Decision.Approve)
        assertThat(rule.evaluate(limit("0.5", "64000"), Held(null))).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `a group without option margin is judged leg by leg and refused on the first refusal`() {
        val cheap = limit("0.1", "32000")
        val dear = limit("1", "64000")

        assertThat(rule.evaluateGroup(listOf(cheap, cheap), Held(null))).isEqualTo(Decision.Approve)
        assertThat(rule.evaluateGroup(listOf(cheap, dear), Held(null))).isInstanceOf(Decision.Reject::class.java)
    }
}
