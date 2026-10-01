package com.qkt.connector.gateway

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GatewayClientTest {
    private val server = MockWebServer().apply { start() }
    private val client =
        GatewayClient(server.url("/").toString().trimEnd('/'), "secret", httpTimeoutMs = 500, retryAttempts = 3)
    private val order =
        """{"client_order_id":"c1","venue_order_id":"9","symbol":"BTC_USDC-25DEC26-92000-C","side":"sell",""" +
            """"type":"limit","quantity":"0.10","limit_price":"650","time_in_force":"gtc","status":"working",""" +
            """"filled_quantity":"0","created_at":1,"updated_at":1}"""
    private val submit = WireSubmit("c1", "BTC_USDC-25DEC26-92000-C", "sell", "limit", "0.10", "650", null, "gtc")

    @AfterEach
    fun stop() = server.shutdown()

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `reads send the bearer token and decode exact decimal strings`() {
        server.enqueue(
            json(
                200,
                """{"currency":"USDC","balance":"10000","equity":"10012.50","margin_used":"265","margin_available":"9747.5"}""",
            ),
        )

        val account = client.account()

        assertThat(account.equity).isEqualTo("10012.50")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/account")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret")
    }

    @Test
    fun `a submit posts the order once and returns what the gateway placed`() {
        server.enqueue(json(201, order))

        val result = client.submit(submit)

        assertThat((result as GatewaySubmit.Placed).order.venueOrderId).isEqualTo("9")
        val body = server.takeRequest().body.readUtf8()
        assertThat(
            body,
        ).contains("\"client_order_id\":\"c1\"").contains("\"quantity\":\"0.10\"").doesNotContain("stop_price")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a submit that times out or meets 503 is sent again with the same body`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(json(503, """{"error":{"code":"venue_unavailable","message":"down"}}"""))
        server.enqueue(json(200, order))

        val result = client.submit(submit)

        assertThat(result).isInstanceOf(GatewaySubmit.Placed::class.java)
        val bodies = (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8() }
        assertThat(bodies.distinct()).hasSize(1)
    }

    @Test
    fun `a venue rejection or the kill switch refuses the order with the gateway's reason`() {
        server.enqueue(json(422, """{"error":{"code":"venue_rejected","message":"not enough funds"}}"""))
        server.enqueue(json(423, """{"error":{"code":"kill_switch","message":"symbols halted"}}"""))

        val rejected = client.submit(submit) as GatewaySubmit.Refused
        val killed = client.submit(submit) as GatewaySubmit.Refused

        assertThat(rejected.code to rejected.message).isEqualTo("venue_rejected" to "not enough funds")
        assertThat(killed.code).isEqualTo("kill_switch")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a conflict or a malformed request is an error, never retried`() {
        server.enqueue(json(409, """{"error":{"code":"conflict","message":"c1 reused"}}"""))

        assertThatThrownBy { client.submit(submit) }
            .isInstanceOf(GatewayException::class.java)
            .hasMessageContaining("conflict")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a submit the gateway never answers fails after the configured attempts`() {
        repeat(3) { server.enqueue(json(503, """{"error":{"code":"venue_unavailable","message":"down"}}""")) }

        assertThatThrownBy { client.submit(submit) }.isInstanceOf(GatewayUnavailableException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `cancel deletes by client order id and returns the order as it ended`() {
        server.enqueue(json(200, order.replace("\"working\"", "\"filled\"")))

        val ended = client.cancel("c1")

        assertThat(ended.status).isEqualTo("filled")
        val request = server.takeRequest()
        assertThat(request.method to request.path).isEqualTo("DELETE" to "/v1/orders/c1")
    }
}
