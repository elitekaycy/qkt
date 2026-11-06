package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.broker.VenueDescribed
import com.qkt.broker.VenueIdentity
import com.qkt.execution.OrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamInstrumentsShapesTest {
    private class Venue :
        Broker,
        VenueDescribed {
        override val name = "EXNESS"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = SubmitAck(request.id, null, accepted = true)

        override fun cancel(orderId: String) {}

        override fun venueIdentity() = VenueIdentity("mt5", "exness", "EXNESS", "acct", "demo")
    }

    @Test
    fun `streams that are not maps or name no symbol are kept as written`() {
        val metadata = mapOf("streams" to listOf("raw", mapOf("alias" to "a")))

        assertThat(InsightsVenues().withStreamInstruments(metadata)).isEqualTo(metadata)
    }

    @Test
    fun `a stream on a described venue gains its venue even without instrument facts`() {
        val metadata = mapOf("streams" to listOf(mapOf("alias" to "a", "qktSymbol" to "EXNESS:XAUUSD")))

        @Suppress("UNCHECKED_CAST")
        val stream =
            (
                InsightsVenues({
                    listOf(Venue())
                }).withStreamInstruments(metadata)["streams"] as List<Map<String, Any?>>
            ).single()

        assertThat(stream).containsEntry("alias", "a").containsKey("venue").doesNotContainKey("instrument")
        assertThat((stream["venue"] as Map<*, *>)["label"]).isEqualTo("EXNESS")
    }

    @Test
    fun `streams that are not a list leave the metadata alone`() {
        val metadata = mapOf("streams" to "gold")

        assertThat(InsightsVenues().withStreamInstruments(metadata)).isSameAs(metadata)
    }
}
