package com.qkt.connector.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Golden shape: an MT5 account and a VGP gateway account read through their real connectors emit every
 * venue envelope (`state.account`, `state.positions`, `state.orders`, `broker.deal`, `broker.connected`)
 * with the same field set, and both state the explicit facts the collector must never infer.
 */
internal class ConnectorEnvelopeShapeTest : ConnectorEnvelopeHarness() {
    @Test
    fun `every venue envelope has one connector-agnostic shape`() {
        val mt5Envelopes = envelopes(mt5Broker(), deployed = setOf("hedge_straddle"))
        val vgpEnvelopes = envelopes(vgpBroker(), deployed = setOf("a"))

        for (type in TYPES) {
            val m = mt5Envelopes.getValue(type)
            val v = vgpEnvelopes.getValue(type)
            assertThat(EnvelopeShapes.paths(v.payload)).describedAs(type).isEqualTo(EnvelopeShapes.paths(m.payload))
            for (fact in FACTS.getValue(type)) {
                assertThat(EnvelopeShapes.present(m, fact)).describedAs("mt5 $type $fact").isTrue()
                assertThat(EnvelopeShapes.present(v, fact)).describedAs("vgp $type $fact").isTrue()
            }
        }
        assertThat(vgpEnvelopes.getValue("broker.connected").payload["broker"]).isEqualTo("DERIBIT")
    }

    private companion object {
        val VENUE = listOf("venue.connector", "venue.adapter", "venue.label", "accountId")
        val FACTS =
            mapOf(
                "state.account" to VENUE + "login",
                "state.positions" to
                    VENUE + listOf("positions[].positionId", "positions[].strategyId", "positions[].attribution") +
                    "positions[].instrument.venueCode",
                "state.orders" to VENUE + listOf("orders[].strategyId", "orders[].attribution"),
                "broker.deal" to
                    VENUE +
                    listOf(
                        "positionId",
                        "legEntry",
                        "closeReason",
                        "profit",
                        "strategyId",
                        "attribution",
                        "realizedSource",
                        "costs[].kind",
                        "costs[].amount",
                        "instrument.venueCode",
                    ),
                "broker.connected" to VENUE,
            )
    }
}
