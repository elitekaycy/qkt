package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.broker.VenueDescribed
import com.qkt.broker.VenueIdentity
import com.qkt.broker.VenueInstrument
import com.qkt.execution.OrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What one unit of a position's quantity is, stated on every instrument so Insights never derives it from the kind. */
class InsightsQuantityUnitTest {
    private class Venue(
        private val instruments: Map<String, VenueInstrument>,
    ) : Broker,
        VenueDescribed {
        override val name = "V"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = SubmitAck(request.id, null, false)

        override fun cancel(orderId: String) = Unit

        override fun venueIdentity() = VenueIdentity("vgp", "x", "V", "vgp:0")

        override fun instrument(qktSymbol: String) = instruments[qktSymbol.substringAfter(':')]
    }

    private fun unit(
        kind: String?,
        coin: String? = null,
    ): Any? {
        val venue = Venue(mapOf("S" to VenueInstrument("S", kind, baseAsset = coin)))
        return InsightsVenues({ listOf(venue) }).instrument("V:S")?.get("quantityUnit")
    }

    @Test
    fun `each kind states its unit`() {
        assertThat(unit("cfd")).isEqualTo("lots")
        assertThat(unit("equity")).isEqualTo("shares")
        assertThat(unit("perpetual")).isEqualTo("contracts")
        assertThat(unit("future")).isEqualTo("contracts")
        assertThat(unit("option")).isEqualTo("contracts")
    }

    @Test
    fun `a coin pair's unit is its coin`() {
        assertThat(unit("spot", coin = "BTC")).isEqualTo("BTC")
    }

    @Test
    fun `an instrument of an unknown kind states no unit`() {
        assertThat(unit(null)).isNull()
        assertThat(unit("spot")).isNull()
    }
}
