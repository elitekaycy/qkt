package com.qkt.connector.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5SendOutcomesTest {
    private fun reply(retcode: Int) =
        MT5OrderResponse(
            result = MT5OrderResult(retcode = retcode, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
            errorMessage = null,
        )

    @Test
    fun `failures after the request left may have executed`() {
        assertThat(MT5SendOutcomes.isAmbiguousSendFailure("IO error: timeout")).isTrue()
        assertThat(MT5SendOutcomes.isAmbiguousSendFailure("HTTP 502 bad gateway")).isTrue()
        assertThat(MT5SendOutcomes.isAmbiguousSendFailure("HTTP 409 duplicate client_order_id")).isTrue()
        assertThat(MT5SendOutcomes.isAmbiguousSendFailure("invalid gateway response after send: <html>")).isTrue()
    }

    @Test
    fun `a venue refusal is a plain rejection`() {
        assertThat(MT5SendOutcomes.isAmbiguousSendFailure("HTTP 400 invalid volume")).isFalse()
        assertThat(MT5SendOutcomes.isAmbiguousSendFailure("retcode=10016")).isFalse()
    }

    @Test
    fun `position-closed and frozen replies mean the venue is finishing the exit itself`() {
        assertThat(MT5SendOutcomes.venueOwnsClose(reply(MT5_TRADE_RETCODE_POSITION_CLOSED), "")).isTrue()
        assertThat(MT5SendOutcomes.venueOwnsClose(reply(MT5_TRADE_RETCODE_FROZEN), "")).isTrue()
        assertThat(MT5SendOutcomes.venueOwnsClose(reply(10016), "invalid stops")).isFalse()
    }

    @Test
    fun `the venue-owned retcode is recognised inside a gateway error body`() {
        val body =
            """HTTP 400 {"result": {"retcode": $MT5_TRADE_RETCODE_POSITION_CLOSED, "comment": "Position closed"}}"""

        assertThat(MT5SendOutcomes.venueOwnsClose(reply(-1), body)).isTrue()
        assertThat(MT5SendOutcomes.venueOwnsClose(reply(-1), """HTTP 400 {"result": {"retcode": 10016}}""")).isFalse()
    }
}
