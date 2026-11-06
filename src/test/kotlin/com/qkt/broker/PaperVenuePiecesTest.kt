package com.qkt.broker

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PaperVenuePiecesTest {
    @Test
    fun `a fractional partial fill refuses a first fraction outside zero to one`() {
        assertThatThrownBy { FractionalPartialFill(BigDecimal.ZERO) }
            .hasMessage("FractionalPartialFill.firstFraction must be in (0, 1): 0")
        assertThatThrownBy { FractionalPartialFill(BigDecimal.ONE) }
            .hasMessage("FractionalPartialFill.firstFraction must be in (0, 1): 1")
    }

    @Test
    fun `a market order is never a resting trigger for the paper broker`() {
        val market = OrderRequest.Market("m", "X", Side.BUY, Money.of("1"), TimeInForce.GTC, 0L)

        assertThat(checkTrigger(market, Tick("X", Money.of("1"), 0L))).isFalse()
    }

    @Test
    fun `booked deals are empty only with neither fills nor settled closes`() {
        assertThat(BookedDeals(emptyList(), emptyList()).isEmpty()).isTrue()
    }
}
