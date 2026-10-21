package com.qkt.broker

import com.qkt.common.Side
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VenueLegTest {
    private val s = "DERIBIT:BTC_USDC_PERPETUAL"

    private fun p(
        qty: String,
        openedAt: Long,
    ) = Position(s, BigDecimal(qty), BigDecimal("100"), openedAt)

    @Test
    fun `opening from flat is IN on a new position named by its open time`() {
        assertThat(VenueLeg.of(null, p("1", 10))).isEqualTo(VenueLeg("IN", "$s#10"))
    }

    @Test
    fun `adding to a position is IN on the same position`() {
        assertThat(VenueLeg.of(p("1", 10), p("2", 10))).isEqualTo(VenueLeg("IN", "$s#10"))
    }

    @Test
    fun `reducing and closing are OUT on the position they reduce`() {
        assertThat(VenueLeg.of(p("2", 10), p("1", 10))).isEqualTo(VenueLeg("OUT", "$s#10"))
        assertThat(VenueLeg.of(p("1", 10), null)).isEqualTo(VenueLeg("OUT", "$s#10"))
        assertThat(VenueLeg.of(p("-1", 10), p("0", 10))).isEqualTo(VenueLeg("OUT", "$s#10"))
    }

    @Test
    fun `crossing zero is INOUT closing one position and opening the next`() {
        assertThat(VenueLeg.of(p("1", 10), p("-1", 20))).isEqualTo(VenueLeg("INOUT", "$s#10", "$s#20"))
    }

    @Test
    fun `the position reopened after a close is a new position`() {
        val first = VenueLeg.of(null, p("1", 10))
        val second = VenueLeg.of(null, p("1", 30))
        assertThat(first.positionId).isNotEqualTo(second.positionId)
    }

    @Test
    fun `a prediction before accounting names the same legs accounting will`() {
        assertThat(VenueLeg.predict(s, null, Side.BUY, BigDecimal.ONE, 10)).isEqualTo(VenueLeg.of(null, p("1", 10)))
        assertThat(VenueLeg.predict(s, p("1", 10), Side.BUY, BigDecimal.ONE, 20))
            .isEqualTo(VenueLeg.of(p("1", 10), p("2", 10)))
        assertThat(VenueLeg.predict(s, p("1", 10), Side.SELL, BigDecimal("2"), 20))
            .isEqualTo(VenueLeg.of(p("1", 10), p("-1", 20)))
        assertThat(VenueLeg.predict(s, p("1", 10), Side.SELL, BigDecimal.ONE, 20).entry).isEqualTo("OUT")
    }
}
