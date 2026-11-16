package com.qkt.connector.mt5

import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A client sharing a read cache: every order, cancel, close and modify drops the cached snapshots. */
class MT5ClientReadCacheTest {
    private val gateway = Mt5RoutedGateway()
    private val client =
        MT5Client(
            gateway.url,
            MT5ServerTimeZone.UTC,
            httpTimeoutMs = 2_000L,
            retryAttempts = 0,
            readCache = MT5ReadCache(600_000L),
        )
    private val done = """{"result":{"retcode":10009,"order":5,"deal":77,"price":2410,"comment":""}}"""
    private val order =
        MT5OrderRequest(symbol = "XAUUSDm", volume = BigDecimal("0.1"), type = "BUY", magic = 1, comment = "e")
    private val routes = listOf("/order", "/orders/9", "/close_position", "/position_close_partial", "/modify_sl_tp")

    @AfterEach
    fun stop() = gateway.close()

    private fun awaited(call: ((MT5OrderResponse) -> Unit) -> Unit): MT5OrderResponse {
        val result = CompletableFuture<MT5OrderResponse>()
        call { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }

    private val blocking: List<Pair<String, () -> Any>> =
        listOf(
            "place" to { client.placeOrder(order) },
            "cancel" to { client.cancelOrder(9L) },
            "modify order" to { client.modifyOrder(9L, MT5OrderModification(price = BigDecimal("2400"))) },
            "close" to { client.closePosition(9L, BigDecimal("0.1")) },
            "modify position" to { client.modifyPosition(9L, sl = BigDecimal("2390")) },
        )

    private val async: List<Pair<String, () -> MT5OrderResponse>> =
        listOf(
            "place" to { awaited { done -> client.placeOrderAsync(order) { done(it) } } },
            "cancel" to { awaited { done -> client.cancelOrderAsync(9L) { done(it) } } },
            "close" to { awaited { done -> client.closePositionAsync(9L) { done(it) } } },
            "partial close" to
                { awaited { done -> client.closePositionAsync(9L, BigDecimal("0.05"), partial = true) { done(it) } } },
            "modify position" to
                { awaited { done -> client.modifyPositionAsync(9L, tp = BigDecimal("2420")) { done(it) } } },
        )

    /** Reads the account twice and reports how many of the two reads reached the gateway. */
    private fun accountReadsAfter(call: () -> Unit): Int {
        client.getAccount()
        val before = gateway.calls("/account").size
        call()
        client.getAccount()
        client.getAccount()
        return gateway.calls("/account").size - before
    }

    @Test
    fun `a snapshot is served from the cache until a call changes the venue`() {
        gateway.on("/account", """{"balance":"100","login":7}""")

        assertThat(accountReadsAfter {}).`as`("nothing changed, so the cached account answers").isZero()
        assertThat(client.getAccount()!!.balance).isEqualByComparingTo("100")
    }

    @Test
    fun `every accepted blocking call drops the cached snapshot once`() {
        gateway.on("/account", """{"balance":"100"}""")
        routes.forEach { gateway.on(it, done) }

        assertThat(blocking.map { (name, call) -> name to accountReadsAfter { call() } })
            .containsExactlyElementsOf(blocking.map { it.first to 1 })
    }

    @Test
    fun `every accepted async call drops the cached snapshot once`() {
        gateway.on("/account", """{"balance":"100"}""")
        routes.forEach { gateway.on(it, done) }

        val outcomes =
            async.map { (name, call) ->
                name to
                    accountReadsAfter { assertThat(call().errorMessage).isNull() }
            }

        assertThat(outcomes).containsExactlyElementsOf(async.map { it.first to 1 })
        assertThat(gateway.calls("/position_close_partial")).containsExactly("POST /position_close_partial")
    }

    @Test
    fun `a call the gateway refuses still drops the cached snapshot`() {
        gateway.on("/account", """{"balance":"100"}""")
        routes.forEach { gateway.fail(it, 500) }

        assertThat(blocking.map { (_, call) -> accountReadsAfter { call() } }).containsOnly(1)
        assertThat(
            async.map { (_, call) ->
                accountReadsAfter { assertThat(call().errorMessage).startsWith("HTTP 500") }
            },
        ).containsOnly(1)
        assertThat(client.cancelOrder(9L)).`as`("a refused cancel has no body to report").isEmpty()
    }

    @Test
    fun `a call whose connection drops still drops the cached snapshot`() {
        gateway.on("/account", """{"balance":"100"}""")
        routes.forEach { gateway.drop(it) }

        assertThat(
            blocking.map { (_, call) ->
                accountReadsAfter { assertThatThrownBy { call() }.isInstanceOf(IOException::class.java) }
            },
        ).containsOnly(1)
        assertThat(
            async.map { (_, call) ->
                accountReadsAfter { assertThat(call().errorMessage).startsWith("IO error") }
            },
        ).containsOnly(1)
    }

    @Test
    fun `a partial close without a volume is refused before any request`() {
        assertThatThrownBy { client.closePositionAsync(9L, partial = true) { } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("partial close requires volume")
        assertThat(gateway.requests).isEmpty()
    }
}
