package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.AllocationMethod
import com.qkt.risk.book.BookLimits
import com.qkt.risk.book.BookRiskConfig
import com.qkt.risk.book.DeRisk
import com.qkt.risk.book.Rung
import com.qkt.strategy.EveryNthTickBuyStrategy
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end validation of the full book-risk layer on the repo's real sample data
 * (`data/sample`, XAUUSD + EURUSD). Runs a two-strategy book with limits + de-risking + dynamic
 * allocation all enabled and confirms the run completes and emits the complete dataset: per-strategy
 * attribution, cross-strategy book analytics, and the book-risk measurement series.
 */
class BookRiskSampleDataValidationTest {
    private val sample = Path.of("data/sample")

    private val bookRisk =
        BookRiskConfig(
            capital = BigDecimal("10000"),
            limits = BookLimits(maxGrossExposure = BigDecimal("5"), maxNetExposure = BigDecimal("5")),
            deRisk = DeRisk(listOf(Rung(BigDecimal("0.05"), BigDecimal("0.5")))),
            allocation = Allocation(method = AllocationMethod.INVERSE_VOL, rebalanceEveryBars = 1),
        )

    @Test
    fun `two-strategy book runs on sample data and emits the full dataset`() {
        val store = DefaultDataStore(root = sample)
        val request =
            MarketRequest(
                symbols = listOf("EURUSD", "XAUUSD"),
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-17T00:00:00Z"),
            )
        val result =
            Backtest
                .fromStore(
                    strategies =
                        listOf(
                            "eur" to EveryNthTickBuyStrategy(symbol = "EURUSD", n = 2, size = Money.of("1")),
                            "xau" to EveryNthTickBuyStrategy(symbol = "XAUUSD", n = 2, size = Money.of("1")),
                        ),
                    store = store,
                    request = request,
                    startingBalance = BigDecimal("10000"),
                    bookRiskConfig = bookRisk,
                ).run()

        // The portfolio runs and trades.
        assertThat(result.global.tradeCount).isGreaterThan(0)
        // Per-strategy attribution for both children.
        assertThat(result.perStrategy.keys).containsExactlyInAnyOrder("eur", "xau")
        // Cross-strategy book analytics present.
        assertThat(result.bookAnalytics).isNotNull
        assertThat(result.bookAnalytics!!.contributionToReturn.keys).containsExactlyInAnyOrder("eur", "xau")
        // Book-risk measurement series present with exposure.
        assertThat(result.bookRisk).isNotNull
        assertThat(result.bookRisk!!.series).isNotEmpty
        assertThat(result.bookRisk!!.maxGrossExposure).isGreaterThan(BigDecimal.ZERO)
    }
}
