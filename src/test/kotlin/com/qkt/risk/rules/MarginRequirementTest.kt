package com.qkt.risk.rules

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.accounting.margin.MarginModel
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
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

/** BTCUSDT quarterly at 5% initial margin on notional, priced 64000: 0.5 BTC needs 1600. */
class MarginRequirementTest {
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
    private val prices = MarketPriceTracker().apply { update(dec, BigDecimal("64000")) }
    private var equity = BigDecimal("1000")
    private val rule =
        MarginRequirement(
            MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
            prices,
        ) { equity }

    private class Book(
        private val held: Map<String, BigDecimal> = emptyMap(),
        private val pendingBuys: Map<String, BigDecimal> = emptyMap(),
    ) : PositionProvider {
        override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal("64000")) }

        override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal("64000")) }

        override fun pendingOrderQuantity(
            symbol: String,
            side: Side,
            strategyId: String?,
        ): BigDecimal = if (side == Side.BUY) pendingBuys[symbol] ?: BigDecimal.ZERO else BigDecimal.ZERO

        override fun pendingEntrySymbols(strategyId: String?) = pendingBuys.keys
    }

    private fun market(
        side: Side,
        qty: String,
        symbol: String = dec,
    ) = OrderRequest.Market("o", symbol, side, BigDecimal(qty), TimeInForce.GTC, 0L, strategyId = "s")

    @Test
    fun `an open the account cannot margin is refused with the figures`() {
        val decision = rule.evaluate(market(Side.BUY, "0.5"), Book())

        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("1600").contains("1000")
    }

    @Test
    fun `an open within equity passes`() {
        equity = BigDecimal("2000")

        assertThat(rule.evaluate(market(Side.BUY, "0.5"), Book())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `held positions and pending entries take margin too`() {
        equity = BigDecimal("2500")

        assertThat(rule.evaluate(market(Side.BUY, "0.3"), Book(held = mapOf(dec to BigDecimal("0.5")))))
            .isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(market(Side.BUY, "0.3"), Book(pendingBuys = mapOf(dec to BigDecimal("0.5")))))
            .isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `reducing a position is never refused for margin`() {
        equity = BigDecimal.ZERO

        assertThat(rule.evaluate(market(Side.SELL, "0.5"), Book(held = mapOf(dec to BigDecimal("1")))))
            .isEqualTo(Decision.Approve)
    }

    @Test
    fun `a flip is judged on the larger exposure it can leave`() {
        equity = BigDecimal("1700")

        assertThat(rule.evaluate(market(Side.SELL, "1.0"), Book(held = mapOf(dec to BigDecimal("0.5")))))
            .isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(market(Side.SELL, "1.2"), Book(held = mapOf(dec to BigDecimal("0.5")))))
            .isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `symbols without margin terms are not its concern`() {
        equity = BigDecimal.ZERO

        assertThat(rule.evaluate(market(Side.BUY, "5", symbol = "EXNESS:XAUUSD"), Book())).isEqualTo(Decision.Approve)
    }
}
