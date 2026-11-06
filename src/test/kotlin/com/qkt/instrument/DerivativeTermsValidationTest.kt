package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DerivativeTermsValidationTest {
    private fun option(
        fee: String = "0",
        rate: String = "0",
    ) = OptionTerms(
        root = "DERIBIT:BTC_USDC",
        underlyingIndex = "btc_usdc",
        strike = BigDecimal("60000"),
        right = OptionRight.CALL,
        expiryMs = 1L,
        tickSteps = TickSteps(BigDecimal("5")),
        exchangeFeePerContract = BigDecimal(fee),
        takerFeeRate = BigDecimal(rate),
    )

    @Test
    fun `option fees must be non-negative and the taker rate under one`() {
        assertThatThrownBy { option(fee = "-0.1") }.hasMessageContaining("OptionTerms.exchangeFeePerContract")
        assertThatThrownBy { option(rate = "1") }.hasMessageContaining("OptionTerms.takerFeeRate must be in [0, 1)")
        assertThatThrownBy { option(rate = "-0.1") }.hasMessageContaining("OptionTerms.takerFeeRate")
        assertThat(option(rate = "0.0003").takerFeeRate).isEqualByComparingTo("0.0003")
    }

    @Test
    fun `a futures taker rate outside zero to one is refused`() {
        assertThatThrownBy { FutureTerms("CME:ES", 1L, takerFeeRate = BigDecimal("1")) }
            .hasMessageContaining("FutureTerms.takerFeeRate must be in [0, 1)")
        assertThatThrownBy { FutureTerms("CME:ES", 1L, takerFeeRate = BigDecimal("-0.5")) }
            .hasMessageContaining("FutureTerms.takerFeeRate")
    }

    @Test
    fun `a listed contract with a negative or non-numeric volume is refused`() {
        assertThatThrownBy { ListedContract("ESZ6", 1L, lifetimeVolume = "-3") }
            .hasMessage("ListedContract.lifetimeVolume must be a number >= 0: -3")
        assertThatThrownBy { ListedContract("ESZ6", 1L, peakOpenInterest = "lots") }
            .hasMessageContaining("ListedContract.peakOpenInterest must be a number >= 0: lots")
        assertThat(ListedContract("ESZ6", 1L, lifetimeVolume = "0").lifetimeVolumeOrNull()).isEqualByComparingTo("0")
    }

    @Test
    fun `a margin basis other than per_contract or notional is refused`() {
        val fields = RootFields("futures")

        assertThatThrownBy { fields.margin(mapOf("initial" to 1, "maintenance" to 1, "basis" to "cross"), "CME:ES") }
            .hasMessage("futures root CME:ES: margin basis must be per_contract or notional")
        assertThat(
            fields.margin(mapOf("initial" to "0.2", "maintenance" to "0.1", "basis" to "NOTIONAL"), "CME:ES").basis,
        ).isEqualTo(MarginBasis.NOTIONAL)
    }
}
