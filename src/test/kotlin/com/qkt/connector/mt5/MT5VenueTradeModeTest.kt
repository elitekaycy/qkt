package com.qkt.connector.mt5

import com.qkt.broker.VenueIdentity
import com.qkt.observe.insights.venuePayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5VenueTradeModeTest {
    private fun mode(tradeMode: Int) =
        MT5VenueDescription.identity("EXNESS_P1", "476422618", "Exness-MT5Trial9", tradeMode).tradeMode

    @Test
    fun `an MT5 account names its environment from the account's trade mode`() {
        assertThat(mode(0)).isEqualTo("demo")
        assertThat(mode(1)).isEqualTo("contest")
        assertThat(mode(2)).isEqualTo("real")
        assertThat(mode(-1)).isNull()
        assertThat(MT5VenueDescription.identity("EXNESS_P1", null, null).tradeMode).isNull()
    }

    @Test
    fun `the venue object of every venue envelope carries the trade mode when it is known`() {
        val identity = VenueIdentity("mt5", "exness", "EXNESS", "mt5:0123456789abcdef", "demo")

        assertThat(venuePayload(identity)["venue"]).isEqualTo(
            mapOf("connector" to "mt5", "adapter" to "exness", "label" to "EXNESS", "tradeMode" to "demo"),
        )
    }
}
