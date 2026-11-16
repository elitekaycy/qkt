package com.qkt.connector.gateway

import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** An amend sends each field it is given and refuses one that changes nothing. */
class GatewayAmendFieldsTest {
    private val server = MockWebServer().apply { start() }
    private val client = GatewayClient(server.url("/").toString().trimEnd('/'), "secret", retryAttempts = 1)
    private val amended =
        """{"client_order_id":"c1","symbol":"S","side":"buy","type":"stop_limit","quantity":"3",""" +
            """"limit_price":"101",""" +
            """"stop_price":"100","time_in_force":"gtc","status":"new","created_at":1,"updated_at":2}"""

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `an amend of quantity and both prices sends all three`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(amended))

        val order = client.amend("c1", BigDecimal("3"), BigDecimal("101"), BigDecimal("100"))

        assertThat(order.quantity).isEqualTo("3")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.path).isEqualTo("/v1/orders/c1")
        assertThat(request.body.readUtf8()).isEqualTo("""{"quantity":"3","limit_price":"101","stop_price":"100"}""")
    }

    @Test
    fun `an amend of the stop alone sends the stop alone`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(amended))

        client.amend("c1", quantity = null, limitPrice = null, stopPrice = BigDecimal("99.5"))

        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"stop_price":"99.5"}""")
    }

    @Test
    fun `an amend that changes nothing is refused before any request`() {
        assertThatThrownBy { client.amend("c1", null, null, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("amend needs a quantity or a price")
        assertThat(server.requestCount).isZero()
    }
}
