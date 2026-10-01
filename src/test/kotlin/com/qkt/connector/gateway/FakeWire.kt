package com.qkt.connector.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse

/** The JSON a [FakeGateway] answers with. */
internal object FakeWire {
    private val json = Json { encodeDefaults = true }
    const val ACCOUNT =
        """{"currency":"USDC","balance":"1000","equity":"1000","margin_used":"0","margin_available":"1000"}"""

    /** A stream event's data for a venue change. */
    fun encode(data: Any): JsonElement =
        when (data) {
            is WireOrder -> json.encodeToJsonElement(WireOrder.serializer(), data)
            is WireFill -> json.encodeToJsonElement(WireFill.serializer(), data)
            is WireSettlement -> json.encodeToJsonElement(WireSettlement.serializer(), data)
            else -> error("no event for $data")
        }

    /** An option instrument listed under [code]. */
    fun instrument(code: String) =
        WireInstrument(
            code,
            "option",
            "USDC",
            "1",
            "5",
            "0.01",
            "0.01",
            underlying = code.substringBefore('-'),
        )

    /** A `200` JSON answer. */
    fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** An error answer with the VGP v1 envelope. */
    fun error(
        status: Int,
        code: String,
        message: String,
    ) = ok("""{"error":{"code":"$code","message":"$message"}}""").setResponseCode(status)
}
