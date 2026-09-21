package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The payload below is what the gateway's `/orders` really returned for a resting buy limit on
 * 2026-09-21 (fields trimmed, values kept). The fixtures these parsers were written against used
 * `"volume"` and a named `"type"`; the gateway sends neither.
 */
class MT5PendingOrderRealPayloadTest {
    private val parser = MT5SnapshotParser(Json { ignoreUnknownKeys = true }, MT5VenueTime(MT5ServerTimeZone.UTC))
    private val real =
        """{"ticket":3260394848,"type":2,"type_str":"BUY_LIMIT","volume_current":0.01,"volume_initial":0.01,
            "price_open":82976.08,"time_setup":1790003221,"time_setup_msc":1790003221276,
            "comment":"dsl-atto_flatten_unknown-","magic":996980,"symbol":"BTCUSDm","sl":0.0,"tp":0.0,"time_expiration":0}"""

    @Test
    fun `a resting order has its size and its named type`() {
        val order = parser.parsePendingOrder(Json.parseToJsonElement(real).jsonObject)

        assertThat(order.volume).isEqualByComparingTo("0.01")
        assertThat(order.type).isEqualTo("BUY_LIMIT")
        assertThat(order.clientOrderId).`as`("the gateway does not echo a client order id on resting orders").isNull()
    }

    @Test
    fun `it is recognised as the placement whose response was lost`() {
        val order = parser.parsePendingOrder(Json.parseToJsonElement(real).jsonObject)
        val placement =
            MT5OrderRequest(
                symbol = "BTCUSDm",
                volume = BigDecimal("0.01"),
                type = "BUY_LIMIT",
                price = BigDecimal("82976.08000000"),
                magic = 996980,
                comment = "dsl-atto_flatten_unknown--2",
            )

        assertThat(
            MT5UnknownOutcomeMatching.matchesUnknownPending(
                order,
                placement,
                placementStartedAtMs = 1_790_003_215_000L,
            ),
        ).isTrue()
    }
}
