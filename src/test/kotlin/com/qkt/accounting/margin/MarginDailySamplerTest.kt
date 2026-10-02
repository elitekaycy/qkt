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
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarginDailySamplerTest {
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
    private val held = mutableMapOf<String, BigDecimal>()
    private val positions =
        object : PositionProvider {
            override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal("60000")) }

            override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal("60000")) }
        }
    private var equity = BigDecimal("2000")
    private val sampler =
        MarginDailySampler(
            MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
            prices,
            positions,
        )

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `each day that ends holding a margined position gets one row with the day's last marks`() {
        sampler.bind { equity }
        prices.update(dec, BigDecimal("60000"))
        held[dec] = BigDecimal("0.5")
        sampler.onTime(ms("2024-09-20T10:00:00Z"))
        prices.update(dec, BigDecimal("64000"))
        sampler.onTime(ms("2024-09-20T23:00:00Z"))
        equity = BigDecimal("700")
        sampler.onTime(ms("2024-09-21T01:00:00Z"))

        val rows = sampler.rows

        assertThat(rows.map { it.date }).containsExactly(LocalDate.parse("2024-09-20"), LocalDate.parse("2024-09-21"))
        assertThat(rows[0].marginUsed).isEqualByComparingTo("1600")
        assertThat(rows[0].maintenance).isEqualByComparingTo("800")
        assertThat(rows[0].marginCall).isFalse()
        assertThat(rows[1].equity).isEqualByComparingTo("700")
        assertThat(rows[1].marginCall).isTrue()
    }

    @Test
    fun `days without margined positions write nothing`() {
        sampler.bind { equity }
        sampler.onTime(ms("2024-09-20T10:00:00Z"))
        sampler.onTime(ms("2024-09-21T10:00:00Z"))

        assertThat(sampler.rows).isEmpty()
    }
}
