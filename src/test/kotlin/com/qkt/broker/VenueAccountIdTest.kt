package com.qkt.broker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VenueAccountIdTest {
    @Test
    fun `the ids are the ones qkt-insights computes`() {
        // The same vectors as qkt-insights' contract accountIdentity: one algorithm in both repos.
        assertThat(VenueAccountId.mt5("12345678", "Broker-Server")).isEqualTo("mt5:ce1e6b4ae096518b")
        assertThat(VenueAccountId.vgp("deribit", "acct-1")).isEqualTo("vgp:b03049e46d1d8972")
    }

    @Test
    fun `an id names neither the login nor the server`() {
        val id = VenueAccountId.mt5("90813377", "Venue-MT5Trial42")
        assertThat(id).matches("mt5:[0-9a-f]{16}").doesNotContain("90813377").doesNotContain("Venue")
        assertThat(VenueAccountId.vgp("deribit", "gw-acct-5521")).matches("vgp:[0-9a-f]{16}")
    }

    @Test
    fun `a login is shown by its last four characters`() {
        assertThat(VenueAccountId.maskLogin("435898347")).isEqualTo("•••8347")
        assertThat(VenueAccountId.maskLogin("7")).isEqualTo("•••7")
        assertThat(VenueAccountId.maskLogin("•••8347")).isEqualTo("•••8347")
    }
}
