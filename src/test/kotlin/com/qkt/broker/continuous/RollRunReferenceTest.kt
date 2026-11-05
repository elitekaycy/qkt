package com.qkt.broker.continuous

import com.qkt.common.Side
import com.qkt.derivatives.futures.MeasuredRoll
import com.qkt.derivatives.futures.RollPrices
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a roll's old-contract leg is collared around. The roll was measured 77,797.5 -> 75,463.575, the new contract
 * 3% under its market (a thin last bar); both contracts trade near 77,920. With no old-contract quote heard, the new
 * contract's quote less the measured spread is 80,261, the adjusted stream's price, which no SELL collared 1% under
 * it can reach.
 */
class RollRunReferenceTest {
    private val measured = MeasuredRoll(0, RollPrices(BigDecimal("77797.5"), BigDecimal("75463.575")))

    private fun run(
        from: String?,
        to: String?,
    ) = RollRun(0, 1, measured, "", emptyList(), emptyList()).apply {
        quotes = RollQuotes(from?.let { tick("OLD", it) }, to?.let { tick("NEW", it) })
    }

    private fun tick(
        symbol: String,
        price: String,
    ) = Tick(symbol, BigDecimal(price), 0)

    @Test
    fun `an old-contract leg is collared around the old contract's own quote when one was heard`() {
        val run = run(from = "77918.5", to = "77927.5")

        assertThat(run.fromReference(Side.SELL)).isEqualByComparingTo("77918.5")
        assertThat(run.fromReference(Side.BUY)).isEqualByComparingTo("77918.5")
    }

    @Test
    fun `with no old-contract quote a selling leg is not collared around the adjusted stream`() {
        val run = run(from = null, to = "77927.5")

        assertThat(run.fromReference(Side.SELL)).isEqualByComparingTo("77927.5")
    }

    @Test
    fun `with no old-contract quote a buying leg keeps the measured spread when it lets the leg fill`() {
        val run = run(from = null, to = "77927.5")

        assertThat(run.fromReference(Side.BUY)).isEqualByComparingTo("80261.425")
    }

    @Test
    fun `with no quote at all an old-contract leg is collared around the measured price`() {
        assertThat(run(from = null, to = null).fromReference(Side.SELL)).isEqualByComparingTo("77797.5")
    }
}
