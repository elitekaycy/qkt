package com.qkt.connector.gateway

import com.qkt.common.Clock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GatewayListingTest {
    private var now = 1_000L
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private var reads = 0
    private var down = false

    private fun instrument(code: String) = WireInstrument(code, "spot", "USD", "1", "0.01", "1", "1")

    private val listing =
        GatewayListing(clock, maxAgeMs = 5_000) {
            if (down) error("gateway down")
            reads++
            listOf(instrument("A$reads"))
        }

    @Test
    fun `a burst of asks is one read, and the listing is read again once it is five seconds old`() {
        assertThat(listing.latest().single().code).isEqualTo("A1")
        now += 4_999
        assertThat(listing.latest().single().code).isEqualTo("A1")
        assertThat(reads).isEqualTo(1)

        now += 1
        assertThat(listing.latest().single().code).isEqualTo("A2")
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `a read that fails is not kept, so the next ask reads again`() {
        down = true
        assertThatThrownBy { listing.latest() }.hasMessageContaining("gateway down")

        down = false
        assertThat(listing.latest().single().code).isEqualTo("A1")
        now += 5_000
        down = true
        assertThatThrownBy { listing.latest() }.hasMessageContaining("gateway down")
    }
}
