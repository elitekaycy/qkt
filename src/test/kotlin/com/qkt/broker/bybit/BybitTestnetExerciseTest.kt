package com.qkt.broker.bybit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException

/**
 * Live Bybit **testnet** exercise for #73. Runs only when `BYBIT_API_KEY` / `BYBIT_API_SECRET`
 * are present in the environment — otherwise every test self-skips, so CI (which has no keys) is
 * unaffected. This is the re-runnable harness for the parts of #73 that need a real exchange:
 * the signed-GET read/reconcile path (the G4 area) and an order submit+cancel round-trip.
 *
 * Safety: it forces `testnet = true` and asserts the resolved base URL is `api-testnet.bybit.com`
 * before any authenticated call. It never touches mainnet.
 *
 * Run it with: `set -a; . ../qrypto/.env; set +a; BYBIT_TESTNET=true ./gradlew test --tests
 * "com.qkt.broker.bybit.BybitTestnetExerciseTest"`.
 */
class BybitTestnetExerciseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(): BybitClient {
        assumeTrue(
            System.getenv("BYBIT_API_KEY") != null && System.getenv("BYBIT_API_SECRET") != null,
            "BYBIT_API_KEY/SECRET not set — skipping live testnet exercise",
        )
        val c = BybitClient(testnet = true)
        assertThat(c.restBaseUrl)
            .withFailMessage("refusing to run against a non-testnet base URL: ${c.restBaseUrl}")
            .contains("api-testnet.bybit.com")
        return c
    }

    private fun retCode(body: String): Int? =
        (json.parseToJsonElement(body) as JsonObject)["retCode"]?.jsonPrimitive?.intOrNull

    private fun retMsg(body: String): String =
        (json.parseToJsonElement(body) as JsonObject)["retMsg"]?.jsonPrimitive?.content ?: ""

    /** A read-scoped key returns retCode 10005 / "permissions for action" on any trade endpoint. */
    private fun isPermissionDenied(
        code: Int?,
        msg: String,
    ): Boolean = code == 10005 || msg.contains("permission", ignoreCase = true)

    @Test
    fun `testnet read endpoints reconcile cleanly`() {
        val c = client()

        val wallet = c.getSigned("/v5/account/wallet-balance", mapOf("accountType" to c.accountType))
        assertThat(retCode(wallet)).withFailMessage("wallet-balance: %s", wallet).isEqualTo(0)

        val orders =
            c.getSigned("/v5/order/realtime", mapOf("category" to "spot", "openOnly" to "0", "limit" to "50"))
        assertThat(retCode(orders)).withFailMessage("order/realtime: %s", orders).isEqualTo(0)

        val execs = c.getSigned("/v5/execution/list", mapOf("category" to "spot", "limit" to "50"))
        assertThat(retCode(execs)).withFailMessage("execution/list: %s", execs).isEqualTo(0)

        val positions =
            c.getSigned("/v5/position/list", mapOf("category" to "linear", "settleCoin" to "USDT"))
        assertThat(retCode(positions)).withFailMessage("position/list: %s", positions).isEqualTo(0)
    }

    @Test
    fun `testnet order placement round-trips or reports read-only`() {
        val c = client()
        // A far-below-market limit buy (rests, never fills); we cancel it immediately. Tiny size.
        val create =
            """{"category":"spot","symbol":"BTCUSDT","side":"Buy","orderType":"Limit",""" +
                """"qty":"0.001","price":"10000","timeInForce":"GTC"}"""
        // Bybit signals a business error (e.g. read-only key -> 10005) in a 200 body, not an HTTP
        // error; the broker layer parses retCode itself, so postSigned returns the body. An HTTP
        // error still throws BybitApiException, which we treat the same way. A read-only key skips
        // this test (order-flow is genuinely not validated); any other error is a real finding.
        val resp =
            try {
                c.postSigned("/v5/order/create", create)
            } catch (e: BybitApiException) {
                if (isPermissionDenied(e.retCode, e.retMsg)) {
                    throw TestAbortedException(
                        "read-only key (retCode=${e.retCode}): order-flow E2E needs a trade-enabled key",
                    )
                }
                throw e
            }

        val code = retCode(resp)
        if (code != 0) {
            if (isPermissionDenied(code, retMsg(resp))) {
                throw TestAbortedException("read-only key (retCode=$code): order-flow E2E needs a trade-enabled key")
            }
            throw AssertionError("order/create rejected for a non-permission reason: $resp")
        }

        // retCode == 0 -> the key has trade scope: validate the full submit + cancel round-trip.
        val orderId =
            (json.parseToJsonElement(resp) as JsonObject)["result"]
                ?.jsonObject
                ?.get("orderId")
                ?.jsonPrimitive
                ?.content
        assertThat(orderId).isNotNull()
        val cancel = c.postSigned("/v5/order/cancel", """{"category":"spot","symbol":"BTCUSDT","orderId":"$orderId"}""")
        assertThat(retCode(cancel)).withFailMessage("order/cancel: %s", cancel).isEqualTo(0)
    }
}
