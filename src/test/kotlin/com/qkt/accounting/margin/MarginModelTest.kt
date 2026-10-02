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
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarginModelTest {
    private fun root(
        name: String,
        currency: String,
        multiplier: String,
        margin: MarginTerms?,
    ) = FuturesRoot(
        name,
        currency,
        BigDecimal(multiplier),
        BigDecimal("0.25"),
        BigDecimal.ONE,
        BigDecimal.ONE,
        null,
        null,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        margin,
    )

    private val es =
        root("CME:ES", "USD", "50", MarginTerms(BigDecimal("14000"), BigDecimal("12700"), MarginBasis.PER_CONTRACT))
    private val btc =
        root(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            "1",
            MarginTerms(BigDecimal("0.05"), BigDecimal("0.025"), MarginBasis.NOTIONAL),
        )
    private val bare = root("CME:NQ", "USD", "20", null)
    private val registry =
        ContractCatalogRegistry(
            listOf(es, btc, bare),
            mapOf(
                es.root to ContractCatalog(es.root, listOf(ListedContract("ESZ24", 1_734_710_400_000L))),
                btc.root to ContractCatalog(btc.root, listOf(ListedContract("BTCUSDT_241227", 1_735_286_400_000L))),
                bare.root to ContractCatalog(bare.root, listOf(ListedContract("NQZ24", 1_734_710_400_000L))),
            ),
        )
    private val model = MarginModel(registry, accountingEngine(AccountingConfig(), MarketPriceTracker(), registry))

    @Test
    fun `per-contract margin scales with the number of contracts, either side`() {
        assertThat(model.initial("CME:ESZ24", BigDecimal("-2"), BigDecimal("6000"), 0L)).isEqualByComparingTo("28000")
        assertThat(
            model.maintenance("CME:ESZ24", BigDecimal("2"), BigDecimal("6000"), 0L),
        ).isEqualByComparingTo("25400")
    }

    @Test
    fun `notional margin is a rate of quantity times price times multiplier`() {
        assertThat(model.initial("BINANCE_UM:BTCUSDT_241227", BigDecimal("0.5"), BigDecimal("64000"), 0L))
            .isEqualByComparingTo("1600")
        assertThat(model.initial("BINANCE_UM:BTCUSDT@front", BigDecimal("0.5"), BigDecimal("64000"), 0L))
            .isEqualByComparingTo("1600")
    }

    @Test
    fun `instruments without margin terms need none`() {
        assertThat(model.initial("CME:NQZ24", BigDecimal("1"), BigDecimal("20000"), 0L)).isEqualByComparingTo("0")
        assertThat(model.initial("EXNESS:XAUUSD", BigDecimal("1"), BigDecimal("2600"), 0L)).isEqualByComparingTo("0")
        assertThat(model.hasTerms("CME:ESZ24")).isTrue()
        assertThat(model.hasTerms("CME:NQZ24")).isFalse()
    }
}
