package com.qkt.connector.mt5

import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Close and protection calls on an open position, by ticket. */
class MT5PositionCallsTest {
    private val gateway = Mt5RoutedGateway()
    private val json = Json { ignoreUnknownKeys = true }
    private val done = """{"result":{"retcode":10009,"order":0,"deal":77,"price":2410,"comment":""}}"""

    @AfterEach
    fun stop() = gateway.close()

    private fun calls(url: String = gateway.url) =
        MT5PositionCalls(OkHttpClient(), url, "k", json, MT5OrderResponses(json), MT5ReadCache(ttlMs = 1_000L))

    private fun unreachableUrl(): String {
        val closed = MockWebServer()
        closed.start()
        val url = closed.url("/").toString().trimEnd('/')
        closed.shutdown()
        return url
    }

    private fun awaited(call: ((MT5OrderResponse) -> Unit) -> Unit): MT5OrderResponse {
        val result = CompletableFuture<MT5OrderResponse>()
        call { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a whole close names only the ticket`() {
        gateway.on("/close_position", done)

        val response = calls().closePosition(42L)

        assertThat(response.result.deal).isEqualTo(77L)
        val body = gateway.requests.single().second
        assertThat(body).contains("\"ticket\":42").doesNotContain("volume")
    }

    @Test
    fun `an asynchronous whole close goes to the close route`() {
        gateway.on("/close_position", done)

        val response = awaited { onResult -> calls().closePositionAsync(42L) { onResult(it) } }

        assertThat(response.result.price).isEqualByComparingTo("2410")
        assertThat(gateway.requests.single().first).isEqualTo("POST /close_position")
    }

    @Test
    fun `an asynchronous partial close goes to the partial route with its volume`() {
        gateway.on("/position_close_partial", done)

        awaited { onResult -> calls().closePositionAsync(42L, BigDecimal("0.04"), partial = true) { onResult(it) } }

        val (line, body) = gateway.requests.single()
        assertThat(line).isEqualTo("POST /position_close_partial")
        assertThat(body).contains("0.04")
    }

    @Test
    fun `an unreachable gateway answers an asynchronous close with an IO error`() {
        val response = awaited { onResult -> calls(unreachableUrl()).closePositionAsync(42L) { onResult(it) } }

        assertThat(response.result.retcode).isEqualTo(-1)
        assertThat(response.errorMessage).startsWith("IO error:")
    }

    @Test
    fun `a protection modify with neither level clears both`() {
        gateway.on("/modify_sl_tp", done)

        assertThat(calls().modifyPosition(42L).result.retcode).isEqualTo(MT5_TRADE_RETCODE_DONE)
        val response = awaited { onResult -> calls().modifyPositionAsync(42L) { onResult(it) } }

        assertThat(response.result.retcode).isEqualTo(MT5_TRADE_RETCODE_DONE)
        assertThat(gateway.calls("/modify_sl_tp")).hasSize(2)
        assertThat(gateway.requests.map { it.second }).allMatch { it.contains("\"position\":42") }
    }

    @Test
    fun `an unreachable gateway answers an asynchronous modify with an IO error`() {
        val response = awaited { onResult -> calls(unreachableUrl()).modifyPositionAsync(42L) { onResult(it) } }

        assertThat(response.errorMessage).startsWith("IO error:")
    }
}
