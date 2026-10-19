package com.qkt.connector.gateway

import java.math.BigDecimal
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class GatewayClientBotCallsTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val client = GatewayClient(base, "secret", httpTimeoutMs = 500, retryAttempts = 3)
    private val closing =
        """{"client_order_id":"bot-close-1","symbol":"SOL_USDC-PERPETUAL","side":"sell","type":"market",""" +
            """"quantity":"2","time_in_force":"ioc","reduce_only":true,"status":"filled","filled_quantity":"2",""" +
            """"avg_fill_price":"105.5","created_at":1,"updated_at":1}"""

    @AfterEach
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `close sends symbol quantity and id`() {
        server.enqueue(json(closing))

        val order = client.closePosition("SOL_USDC-PERPETUAL", BigDecimal("2"), "bot-close-1")

        assertThat(order.status).isEqualTo("filled")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/positions/close")
        assertThat(request.body.readUtf8())
            .contains("\"symbol\":\"SOL_USDC-PERPETUAL\"")
            .contains("\"quantity\":\"2\"")
            .contains("\"client_order_id\":\"bot-close-1\"")
    }

    @Test
    fun `close without a quantity closes the whole position`() {
        server.enqueue(json(closing))

        client.closePosition("SOL_USDC-PERPETUAL", null, "bot-close-1")

        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("quantity")
    }

    @Test
    fun `close retried after a 503 sends the same body`() {
        server.enqueue(json("""{"error":{"code":"venue_unavailable","message":"down"}}""").setResponseCode(503))
        server.enqueue(json(closing))

        client.closePosition("SOL_USDC-PERPETUAL", BigDecimal("2"), "bot-close-1")

        val first = server.takeRequest().body.readUtf8()
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo(first)
    }

    @Test
    fun `amend sends only the given fields`() {
        server.enqueue(json(closing))

        client.amend("c1", quantity = null, limitPrice = BigDecimal("104.25"), stopPrice = null)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.path).isEqualTo("/v1/orders/c1")
        assertThat(request.body.readUtf8()).isEqualTo("""{"limit_price":"104.25"}""")
    }

    @Test
    fun `quote once returns the first priced quote`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send("""{"symbol":"SOL_USDC-PERPETUAL","time":5}""")
                        webSocket.send("""{"symbol":"SOL_USDC-PERPETUAL","bid":"105.4","ask":"105.6","time":6}""")
                    }
                },
            ),
        )

        val quote = gatewayQuoteOnce(base, "secret", "SOL_USDC-PERPETUAL", timeoutMs = 5_000)

        assertThat(quote.bid).isEqualTo("105.4")
        assertThat(quote.ask).isEqualTo("105.6")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/quotes?symbols=SOL_USDC-PERPETUAL")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret")
    }

    @Test
    fun `quote once times out with a named error`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

        assertThatThrownBy { gatewayQuoteOnce(base, "secret", "SOL_USDC-PERPETUAL", timeoutMs = 300) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no quote for SOL_USDC-PERPETUAL within")
    }
}
