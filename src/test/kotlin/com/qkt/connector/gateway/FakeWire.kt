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
            is WirePosition -> json.encodeToJsonElement(WirePosition.serializer(), data)
            is WireFunding -> json.encodeToJsonElement(WireFunding.serializer(), data)
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

    /** `GET /v1/bars` over [all] bars by code and window, [pageSize] at a time. */
    fun bars(
        url: okhttp3.HttpUrl,
        all: Map<Pair<String, Long>, List<WireBar>>,
        pageSize: Int,
    ): MockResponse {
        val window = requireNotNull(url.queryParameter("window_ms")).toLong()
        val from = requireNotNull(url.queryParameter("from")).toLong()
        val to = requireNotNull(url.queryParameter("to")).toLong()
        val inRange =
            all[requireNotNull(url.queryParameter("symbol")) to window].orEmpty().filter {
                it.start in
                    from until to
            }
        val next = inRange.getOrNull(pageSize)?.start
        return ok(json.encodeToString(WireBars.serializer(), WireBars(inRange.take(pageSize), next)))
    }

    /** `GET /v1/funding-rates` over [inRange], [pageSize] at a time, `next` the following page's first time. */
    fun rates(
        inRange: List<WireFundingRate>,
        pageSize: Int,
    ): MockResponse {
        val next = inRange.getOrNull(pageSize)?.time
        return ok(json.encodeToString(WireFundingRates.serializer(), WireFundingRates(inRange.take(pageSize), next)))
    }

    /** `/v1/health` for account [login] at [seq], kill switch [killed], declaring [capabilities]. */
    fun health(
        login: String,
        killed: Boolean,
        seq: Int,
        capabilities: List<String>,
    ) = """{"protocol":"vgp1","adapter":"fake","adapter_version":"1","account_login":"$login","trade_mode":"demo",""" +
        """"venue_connected":true,"kill_switch":{"all":$killed},"server_time":${FakeGateway.TIME},""" +
        """"stream":"s1","seq":$seq,"capabilities":${capabilities.joinToString(",", "[", "]") { "\"$it\"" }}}"""

    /** A `200` JSON answer. */
    fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** An error answer with the VGP v1 envelope. */
    fun error(
        status: Int,
        code: String,
        message: String,
    ) = ok("""{"error":{"code":"$code","message":"$message"}}""").setResponseCode(status)
}
