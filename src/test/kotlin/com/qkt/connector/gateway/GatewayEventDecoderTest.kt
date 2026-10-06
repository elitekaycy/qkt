package com.qkt.connector.gateway

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GatewayEventDecoderTest {
    private val funded = mutableListOf<WireFunding>()
    private val decoder = GatewayEventDecoder({}, {}, {}, {}, {}, { funded += it })

    @Test
    fun `a funding event reaches its handler with every field`() {
        val data =
            Json.parseToJsonElement(
                """{"funding_id":"tx-9","symbol":"SOL_USDC-PERPETUAL","amount":"-0.5","currency":"USDC","position":"-150","time":7}""",
            )

        decoder.decode(WireEvent("s", 1, "funding", 7, data))

        assertThat(funded.single()).isEqualTo(WireFunding("tx-9", "SOL_USDC-PERPETUAL", "-0.5", "USDC", "-150", 7))
    }

    @Test
    fun `an event type qkt does not know is ignored, with or without data, and a known one without data is refused`() {
        decoder.decode(WireEvent("s", 1, "liquidation", 7, Json.parseToJsonElement("""{"symbol":"x"}""")))
        decoder.decode(WireEvent("s", 2, "open_interest", 7, null))

        assertThat(funded).isEmpty()
        assertThatThrownBy { decoder.decode(WireEvent("s", 3, "funding", 7, null)) }
            .isInstanceOf(GatewayProtocolException::class.java)
    }
}
