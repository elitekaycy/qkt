package com.qkt.broker.options

import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OptionFeeTest {
    private fun root(
        cap: String? = "0.125",
        flat: String = "0",
    ) = OptionRoot(
        "DERIBIT:BTC_USDC",
        "USDC",
        BigDecimal.ONE,
        TickSteps(BigDecimal("5")),
        BigDecimal("0.01"),
        BigDecimal("0.01"),
        "btc_usdc",
        exchangeFeePerContract = BigDecimal(flat),
        takerFeeRate = BigDecimal("0.0003"),
        feeCapRate = cap?.let(::BigDecimal),
        deliveryFeeRate = BigDecimal("0.00015"),
    )

    private val qty = BigDecimal("0.1")
    private val index = BigDecimal("83000")

    @Test
    fun `a trade pays the rate on the underlying unless the premium cap is lower`() {
        // 0.03% of 83000 = 24.9 per contract; 12.5% of 1500 = 187.5 does not bind.
        assertThat(OptionFee.trade(root(), qty, BigDecimal("1500"), index)).isEqualByComparingTo("2.49")
        // 12.5% of 100 = 12.5 binds below 24.9.
        assertThat(OptionFee.trade(root(), qty, BigDecimal("100"), index)).isEqualByComparingTo("1.25")
        assertThat(OptionFee.trade(root(cap = null), qty, BigDecimal("100"), index)).isEqualByComparingTo("2.49")
        assertThat(OptionFee.trade(root(flat = "0.5"), qty, BigDecimal("1500"), index)).isEqualByComparingTo("2.54")
    }

    @Test
    fun `delivery pays only in the money, capped at the settlement value`() {
        // 0.015% of 95000 = 14.25 per contract; 12.5% of the 3000 intrinsic = 375 does not bind.
        assertThat(
            OptionFee.delivery(root(), qty, BigDecimal("3000"), BigDecimal("95000")),
        ).isEqualByComparingTo("1.425")
        // 12.5% of a 50 intrinsic = 6.25 binds.
        assertThat(OptionFee.delivery(root(), qty, BigDecimal("50"), BigDecimal("92050"))).isEqualByComparingTo("0.625")
        assertThat(OptionFee.delivery(root(), qty, BigDecimal.ZERO, BigDecimal("90000"))).isZero()
    }
}
