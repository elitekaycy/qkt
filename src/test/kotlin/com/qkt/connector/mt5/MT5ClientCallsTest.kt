package com.qkt.connector.mt5

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Order, close, modify and venue-cancel calls through the client facade. */
class MT5ClientCallsTest {
    private val gateway = Mt5RoutedGateway()
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)
    private val done = """{"result":{"retcode":10009,"order":0,"deal":77,"price":2410,"comment":""}}"""

    @AfterEach
    fun stop() = gateway.close()

    private fun awaited(call: ((MT5OrderResponse) -> Unit) -> Unit): MT5OrderResponse {
        val result = CompletableFuture<MT5OrderResponse>()
        call { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `an order the gateway answers with an HTTP error carries that error`() {
        gateway.respond("/order", 502 to "bad gateway")
        val order =
            MT5OrderRequest(symbol = "XAUUSDm", volume = "0.1".toBigDecimal(), type = "BUY", magic = 1, comment = "e")

        val response = client.placeOrder(order)

        assertThat(response.result.retcode).isEqualTo(-1)
        assertThat(response.errorMessage).isEqualTo("HTTP 502: bad gateway")
    }

    @Test
    fun `a whole close and both protection modifies can name the ticket alone`() {
        gateway.on("/close_position", done)
        gateway.on("/modify_sl_tp", done)

        val closed = awaited { onResult -> client.closePositionAsync(42L) { onResult(it) } }
        val modified = client.modifyPosition(42L)
        val modifiedLater = awaited { onResult -> client.modifyPositionAsync(42L) { onResult(it) } }

        assertThat(listOf(closed, modified, modifiedLater).map { it.result.retcode })
            .containsOnly(MT5_TRADE_RETCODE_DONE)
        assertThat(gateway.requests.map { it.first })
            .containsExactly("POST /close_position", "POST /modify_sl_tp", "POST /modify_sl_tp")
    }

    @Test
    fun `a venue order cancel succeeds on a numeric ticket the gateway answers`() {
        gateway.on("/orders/9001", """{"ok":true}""")
        val cancel = MT5VenueOrderCancel(client, "exness")

        assertThat(cancel.cancelVenueOrder("9001")).isTrue()
        assertThat(gateway.requests.single().first).isEqualTo("DELETE /orders/9001")
    }

    @Test
    fun `a venue order cancel fails on a ticket that is not a number or a dropped connection`() {
        gateway.drop("/orders/9001")
        val cancel = MT5VenueOrderCancel(client, "exness")

        assertThat(cancel.cancelVenueOrder("not-a-ticket")).isFalse()
        assertThat(gateway.requests).isEmpty()
        assertThat(cancel.cancelVenueOrder("9001")).isFalse()
    }
}
