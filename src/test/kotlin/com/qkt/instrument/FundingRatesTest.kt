package com.qkt.instrument

import com.qkt.marketdata.store.binance.BinanceFundingRates
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Funding rates read from a venue, stored once per time, and judged for a backtest's window. */
class FundingRatesTest {
    private val hour = 3_600_000L

    private fun fixture(name: String) = javaClass.getResource("/futures/binance/$name")!!.readText()

    @Test
    fun `binance payments read each rate and mark price from its text, an empty mark as none`() {
        val recent = BinanceFundingRates.parse(fixture("funding-rate-btcusdt.json"))
        val old = BinanceFundingRates.parse(fixture("funding-rate-btcusdt-2020.json"))

        assertThat(
            recent.first(),
        ).isEqualTo(FundingRate(1_790_812_800_001L, BigDecimal("0.00007981"), BigDecimal("83582.73222464")))
        // Binance stamps each payment a few milliseconds off the hour, either side of it.
        assertThat(recent.map { it.timeMs }.zipWithNext { a, b -> (b - a + hour / 2) / hour }).containsOnly(8L)
        assertThat(old.first().rate).isEqualByComparingTo("-0.00012359")
        assertThat(old.map { it.price }).containsOnlyNulls()
    }

    @Test
    fun `the store keeps one rate per time, oldest first, in the same file whatever the venue`(
        @TempDir dir: Path,
    ) {
        val store = FundingRateStore(dir)
        store.merge("DERIBIT:BTC_USDC_PERPETUAL", listOf(FundingRate(2 * hour, BigDecimal("0.1"), null)))

        val held =
            store.merge(
                "DERIBIT:BTC_USDC_PERPETUAL",
                listOf(
                    FundingRate(hour, BigDecimal("0.2"), BigDecimal("5")),
                    FundingRate(
                        2 * hour,
                        BigDecimal("0.3"),
                        null,
                    ),
                ),
            )

        assertThat(held).isEqualTo(2)
        assertThat(
            store.read("DERIBIT:BTC_USDC_PERPETUAL")!!.map { it.rate.toPlainString() },
        ).containsExactly("0.2", "0.3")
        assertThat(
            store.path("DERIBIT:BTC_USDC_PERPETUAL"),
        ).isEqualTo(dir.resolve("funding/DERIBIT/BTC_USDC_PERPETUAL.csv"))
        assertThat(store.read("DERIBIT:ETH_USDC_PERPETUAL")).isNull()
    }

    @Test
    fun `a backtest's perpetual needs rates from its start to its end with no day-long gap, a dated contract none`(
        @TempDir dir: Path,
    ) {
        val root =
            FuturesRoot(
                "DERIBIT:BTC_USDC",
                "USDC",
                BigDecimal.ONE,
                BigDecimal("0.5"),
                BigDecimal("0.001"),
                BigDecimal("0.001"),
                null,
                "crypto",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                perpetual = "BTC_USDC_PERPETUAL",
            )
        val day = 24 * hour
        val perp = listOf("DERIBIT:BTC_USDC_PERPETUAL")

        fun problem(
            times: List<Long>,
            symbols: List<String> = perp,
        ): String? {
            val store = FundingRateStore(dir.resolve("r${times.hashCode()}"))
            if (times.isNotEmpty()) {
                store.merge(
                    perp.single(),
                    times.map { FundingRate(it, BigDecimal("0.0001"), null) },
                )
            }
            return FundingCoverage.problem(
                ContractCatalogRegistry(listOf(root), emptyMap(), fundingStore = store),
                symbols,
                0,
                3 * day,
            )
        }

        assertThat(problem((0..9).map { it * 8 * hour })).isNull()
        assertThat(problem(emptyList())).contains("has no stored funding rates").contains("--funding off")
        assertThat(problem(listOf(0L, 8 * hour, 3 * day))).contains("skip 1970-01-01 to 1970-01-04")
        assertThat(problem(listOf(2 * day, 3 * day))).contains("start 1970-01-03")
        assertThat(problem(emptyList(), listOf("DERIBIT:BTC_USDC@front"))).isNull()
    }
}
