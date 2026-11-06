package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OptionShapesValidationTest {
    private fun root(
        name: String = "DERIBIT:BTC_USDC",
        currency: String = "USDC",
        contractSize: String = "1",
        volumeStep: String = "0.01",
        volumeMin: String = "0.01",
        index: String = "btc_usdc",
        markSpread: String? = null,
        chains: QuoteSource? = null,
        maxQuoteAgeMinutes: Int = 60,
        feeCapRate: String? = null,
        deliveryFeeRate: String = "0",
        takerFeeRate: String = "0",
    ) = OptionRoot(
        root = name,
        currency = currency,
        contractSize = BigDecimal(contractSize),
        tickSteps = TickSteps(BigDecimal("5")),
        volumeStep = BigDecimal(volumeStep),
        volumeMin = BigDecimal(volumeMin),
        underlyingIndex = index,
        takerFeeRate = BigDecimal(takerFeeRate),
        chains = chains,
        markSpread = markSpread?.let(::BigDecimal),
        maxQuoteAgeMinutes = maxQuoteAgeMinutes,
        feeCapRate = feeCapRate?.let(::BigDecimal),
        deliveryFeeRate = BigDecimal(deliveryFeeRate),
    )

    @Test
    fun `an option root refuses each malformed field by name`() {
        assertThatThrownBy { root(name = "BTC") }.hasMessageContaining("OptionRoot.root must be VENUE:ROOT")
        assertThatThrownBy { root(currency = "usdc") }.hasMessageContaining("OptionRoot.currency")
        assertThatThrownBy { root(contractSize = "0") }.hasMessageContaining("OptionRoot.contractSize")
        assertThatThrownBy { root(volumeStep = "0") }.hasMessageContaining("OptionRoot.volumeStep")
        assertThatThrownBy { root(volumeMin = "0.001") }.hasMessageContaining("OptionRoot.volumeMin")
        assertThatThrownBy { root(index = " ") }.hasMessageContaining("OptionRoot.underlyingIndex")
        assertThatThrownBy { root(maxQuoteAgeMinutes = 0) }.hasMessageContaining("maxQuoteAgeMinutes")
        assertThatThrownBy { root(deliveryFeeRate = "-1") }.hasMessageContaining("deliveryFeeRate")
        assertThatThrownBy { root(takerFeeRate = "-1") }.hasMessageContaining("takerFeeRate")
    }

    @Test
    fun `mark spread and fee cap are fractions, and a trade-built chain needs a mark spread`() {
        assertThatThrownBy { root(markSpread = "0") }.hasMessageContaining("markSpread must be a fraction")
        assertThatThrownBy { root(markSpread = "1") }.hasMessageContaining("markSpread must be a fraction")
        assertThatThrownBy { root(chains = QuoteSource.TRADE) }.hasMessageContaining("markSpread is required")
        assertThatThrownBy { root(feeCapRate = "0") }.hasMessageContaining("feeCapRate must be a fraction")
        assertThatThrownBy { root(feeCapRate = "1.5") }.hasMessageContaining("feeCapRate must be a fraction")
        val traded = root(chains = QuoteSource.TRADE, markSpread = "0.05", feeCapRate = "1")
        assertThat(traded.venue).isEqualTo("DERIBIT")
        assertThat(root(chains = QuoteSource.BOOK).chains).isEqualTo(QuoteSource.BOOK)
    }

    @Test
    fun `tick steps must be positive, ascending and aligned`() {
        val five = BigDecimal("5")
        assertThatThrownBy { TickSteps(BigDecimal.ZERO) }.hasMessageContaining("base tick must be > 0")
        assertThatThrownBy { TickSteps(five, listOf(TickStep(BigDecimal("100"), BigDecimal.ZERO))) }
            .hasMessageContaining("every step's tick must be > 0")
        assertThatThrownBy {
            TickSteps(five, listOf(TickStep(BigDecimal("200"), five), TickStep(BigDecimal("100"), five)))
        }.hasMessageContaining("must be ascending")
        assertThatThrownBy { TickSteps(five, listOf(TickStep(BigDecimal("-10"), five))) }
            .hasMessageContaining("step above -10 must be a non-negative multiple of its tick 5")
        assertThatThrownBy { TickSteps(five, listOf(TickStep(BigDecimal("12"), five))) }
            .hasMessageContaining("step above 12")
        val steps = TickSteps(five, listOf(TickStep(BigDecimal("100"), BigDecimal("10"))))
        assertThat(steps.tickAt(BigDecimal("100"))).isEqualByComparingTo("5")
        assertThat(steps.tickAt(BigDecimal("101"))).isEqualByComparingTo("10")
    }

    @Test
    fun `option contracts and terms refuse blank, non-positive and malformed fields`() {
        assertThatThrownBy { OptionContract(" ", BigDecimal.ONE, OptionRight.CALL, 1L) }.hasMessageContaining("symbol")
        assertThatThrownBy { OptionContract("C", BigDecimal.ZERO, OptionRight.CALL, 1L) }.hasMessageContaining("strike")
        assertThatThrownBy { OptionContract("C", BigDecimal.ONE, OptionRight.PUT, 0L) }.hasMessageContaining("expiryMs")
        val steps = TickSteps(BigDecimal("5"))
        assertThatThrownBy { OptionTerms("BTC", "i", BigDecimal.ONE, OptionRight.CALL, 1L, steps) }
            .hasMessageContaining("OptionTerms.root")
        assertThatThrownBy { OptionTerms("D:B", " ", BigDecimal.ONE, OptionRight.CALL, 1L, steps) }
            .hasMessageContaining("underlyingIndex")
        assertThatThrownBy { OptionTerms("D:B", "i", BigDecimal.ZERO, OptionRight.CALL, 1L, steps) }
            .hasMessageContaining("OptionTerms.strike")
        assertThatThrownBy { OptionTerms("D:B", "i", BigDecimal.ONE, OptionRight.PUT, 0L, steps) }
            .hasMessageContaining("OptionTerms.expiryMs")
    }
}
