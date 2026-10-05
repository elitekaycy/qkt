package com.qkt.accounting.margin

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.MarginBasis
import com.qkt.instrument.MarginTerms
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaintenanceMarginTest {
    private val dec = "BINANCE_UM:BTCUSDT_241227"
    private val perp = "BINANCE_UM:BTCUSDT"
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
            MarginTerms(BigDecimal("0.10"), BigDecimal("0.05"), MarginBasis.NOTIONAL),
            perpetual = "BTCUSDT",
        )
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to ContractCatalog(root.root, listOf(ListedContract("BTCUSDT_241227", 1_735_286_400_000L))),
            ),
        )
    private val prices = MarketPriceTracker()
    private val held = linkedMapOf<String, BigDecimal>()
    private val positions =
        object : PositionProvider {
            override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal("60000")) }

            override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal("60000")) }

            override fun pendingOrderQuantity(
                symbol: String,
                side: com.qkt.common.Side,
                strategyId: String?,
            ) = BigDecimal("50")
        }
    private val maintenance =
        MaintenanceMargin(
            MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
            prices,
            positions,
        )
    private val now = 1_726_790_400_000L

    @Test
    fun `maintenance is each held position's rate on notional at its last price, pending orders aside`() {
        held[dec] = BigDecimal("1")
        held[perp] = BigDecimal("-2")
        prices.update(Tick(dec, BigDecimal("52000"), now))

        // 5% of 52,000 for the quarterly, 5% of 2 x 60,000 (entry, no last price) for the perpetual.
        assertThat(maintenance.total(now)).isEqualByComparingTo("8600")
    }

    @Test
    fun `symbols without margin terms neither count nor make the account margined`() {
        held["FX:EURUSD"] = BigDecimal("1000")

        assertThat(maintenance.holdsAny()).isFalse()
        assertThat(maintenance.total(now)).isEqualByComparingTo("0")
    }

    @Test
    fun `a flat margined symbol is not held`() {
        held[perp] = BigDecimal.ZERO

        assertThat(maintenance.holdsAny()).isFalse()
        assertThat(maintenance.heldSymbols()).isEmpty()
    }

    @Test
    fun `held margined symbols are listed in sorted order`() {
        held[perp] = BigDecimal("1")
        held[dec] = BigDecimal("1")
        held["FX:EURUSD"] = BigDecimal("1000")

        assertThat(maintenance.holdsAny()).isTrue()
        assertThat(maintenance.heldSymbols()).containsExactly(perp, dec)
    }
}
