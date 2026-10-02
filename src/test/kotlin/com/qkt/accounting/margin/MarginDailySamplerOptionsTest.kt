package com.qkt.accounting.margin

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.app.StructureFixtures
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Option positions count in `margin_daily.csv` at the worst-case requirement the margin rule uses. */
class MarginDailySamplerOptionsTest {
    private val registry = StructureFixtures.registry
    private val prices =
        MarketPriceTracker().apply {
            update(StructureFixtures.P81, BigDecimal("500"))
            update(StructureFixtures.P78, BigDecimal("150"))
        }
    private val held = mapOf(StructureFixtures.P81 to BigDecimal("-0.1"), StructureFixtures.P78 to BigDecimal("0.1"))
    private val positions =
        object : PositionProvider {
            override fun positionFor(symbol: String) = held[symbol]?.let { Position(symbol, it, BigDecimal.ONE) }

            override fun allPositions() = held.mapValues { (s, q) -> Position(s, q, BigDecimal.ONE) }
        }

    @Test
    fun `a put spread's day carries its width less its mark value as margin`() {
        val sampler =
            MarginDailySampler(
                MarginModel(registry, accountingEngine(AccountingConfig(), prices, registry)),
                prices,
                positions,
                OptionMargin(registry),
            )
        sampler.bind { BigDecimal("1000") }

        sampler.onTime(1_790_841_600_000L)

        // Value -0.1 x 500 + 0.1 x 150 = -35; worst payoff -0.1 x 3000 = -300: 265 either way.
        val day = sampler.rows.single()
        assertThat(day.date).isEqualTo(LocalDate.parse("2026-10-01"))
        assertThat(day.marginUsed).isEqualByComparingTo("265")
        assertThat(day.maintenance).isEqualByComparingTo("265")
        assertThat(day.marginCall).isFalse()
    }
}
