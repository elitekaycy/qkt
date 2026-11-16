package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connector.mt5.Mt5RoutedGateway
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Cancels and unanswered submits as the placement thread retries and resolves them; retries are immediate. */
class GatewayPlacementTest {
    private val gateway = Mt5RoutedGateway()
    private val orders = CopyOnWriteArrayList<WireOrder>()
    private val firstOrder = CompletableFuture<WireOrder>()
    private val rejects = CopyOnWriteArrayList<String>()
    private val placement =
        GatewayPlacement(
            GatewayClient(gateway.url, "k", httpTimeoutMs = 2_000, retryAttempts = 1),
            FixedClock(time = 1_000L),
            onOrder = {
                orders.add(it)
                firstOrder.complete(it)
            },
            onFill = {},
            submitDeadlineMs = 0L,
            retryMs = 0L,
        )

    @AfterEach
    fun stop() {
        placement.shutdown()
        gateway.close()
    }

    private fun order(status: String) =
        """{"client_order_id":"c-1","symbol":"BTC-PERP","side":"buy","type":"limit","quantity":"1",""" +
            """"time_in_force":"gtc","status":"$status","created_at":1,"updated_at":1}"""

    private fun failure(code: String) = """{"error":{"code":"$code","message":"$code on purpose"}}"""

    /** Waits until the placement thread has run everything queued before this call, [rounds] times over. */
    private fun drain(rounds: Int) {
        repeat(rounds) {
            val done = CompletableFuture<Unit>()
            placement.background { done.complete(Unit) }
            done.get(5, TimeUnit.SECONDS)
        }
    }

    /** Waits for the order the retry chain ends on: the placement thread retries after each attempt, so a fixed drain can end early. */
    private fun awaitOrder(): WireOrder = firstOrder.get(10, TimeUnit.SECONDS)

    private fun deletes() = gateway.calls("/v1/orders/c-1").filter { it.startsWith("DELETE") }

    @Test
    fun `a cancel of an order the gateway no longer has needs nothing`() {
        gateway.respond("/v1/orders/c-1", 404 to failure("not_found"))

        placement.cancel("c-1")
        drain(2)

        assertThat(orders).isEmpty()
        assertThat(deletes()).hasSize(1)
    }

    @Test
    fun `a cancel the gateway refuses is logged and not sent again`() {
        gateway.respond("/v1/orders/c-1", 409 to failure("conflict"))

        placement.cancel("c-1")
        drain(2)

        assertThat(orders).isEmpty()
        assertThat(deletes()).hasSize(1)
    }

    @Test
    fun `a cancel that meets a server error is sent again until the order ends`() {
        gateway.respond("/v1/orders/c-1", 500 to failure("internal"), 200 to order("cancelled"))

        placement.cancel("c-1")
        awaitOrder()

        assertThat(orders.single().status).isEqualTo("cancelled")
        assertThat(deletes()).hasSize(2)
    }

    @Test
    fun `a cancel the gateway cannot deliver is sent again until it can`() {
        gateway.respond("/v1/orders/c-1", 503 to failure("venue_unavailable"), 200 to order("cancelled"))

        placement.cancel("c-1")
        awaitOrder()

        assertThat(orders.single().status).isEqualTo("cancelled")
        assertThat(deletes()).hasSize(2)
    }

    @Test
    fun `an unanswered submit past its deadline is asked after by id until the gateway can say`() {
        gateway.respond("/v1/orders", 500 to failure("internal"))
        gateway.respond(
            "/v1/orders/c-1",
            503 to failure("venue_unavailable"),
            500 to failure("internal"),
            200 to order("working"),
        )
        gateway.on("/v1/deals", """{"deals":[]}""")
        val submit = WireSubmit("c-1", "BTC-PERP", "buy", "limit", "1", "100", null, "gtc")

        placement.submit(submit) { rejects.add(it) }
        awaitOrder()

        assertThat(rejects).isEmpty()
        assertThat(orders.single().status).isEqualTo("working")
        assertThat(gateway.calls("/v1/orders/c-1")).hasSize(3)
        assertThat(gateway.calls("/v1/deals").single()).contains("client_order_id=c-1")
    }
}
