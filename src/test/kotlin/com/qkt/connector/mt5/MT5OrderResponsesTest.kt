package com.qkt.connector.mt5

import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How the gateway's replies to orders, cancels and modifies are read. */
class MT5OrderResponsesTest {
    private val responses = MT5OrderResponses(Json { ignoreUnknownKeys = true })

    private fun http(
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url("http://gateway/modify_sl_tp").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody())
            .build()

    @Test
    fun `an order reply without a result block is a failure that quotes the reply`() {
        val response = responses.parseOrderResponse("""{"error":"bad symbol"}""")

        assertThat(response.result.retcode).isEqualTo(-1)
        assertThat(response.errorMessage).isEqualTo("""missing result field: {"error":"bad symbol"}""")
    }

    @Test
    fun `a cancel reply that is not JSON, or names no cancel, is unconfirmed`() {
        assertThat(responses.parseCancelResponse("<html>").errorMessage).isEqualTo("invalid cancel response: <html>")
        assertThat(responses.parseCancelResponse("""{"error":"no such order"}""").errorMessage)
            .isEqualTo("no such order")
        assertThat(responses.parseCancelResponse("""{"message":"queued"}""").errorMessage)
            .isEqualTo("""unconfirmed cancel response: {"message":"queued"}""")
    }

    @Test
    fun `a cancel reply that says cancelled, or carries a result, is read as such`() {
        val cancelled = responses.parseCancelResponse("""{"message":"Order cancelled"}""")
        val result = responses.parseCancelResponse("""{"result":{"retcode":10009,"order":5,"deal":0,"price":0}}""")

        assertThat(cancelled.result.retcode).isEqualTo(MT5_TRADE_RETCODE_DONE)
        assertThat(cancelled.result.comment).isEqualTo("Order cancelled")
        assertThat(result.result.order).isEqualTo(5L)
    }

    @Test
    fun `a failed modify is an HTTP error unless the venue said nothing changed`() {
        val failed = responses.parseAsyncModifyResponse(42L, http(400, """{"error":"invalid stops"}"""))
        val unchanged = responses.parseAsyncModifyResponse(42L, http(400, """{"mt5_error":{"retcode":10025}}"""))

        assertThat(failed.errorMessage).isEqualTo("""HTTP 400: {"error":"invalid stops"}""")
        assertThat(unchanged.result.retcode).isEqualTo(MT5_TRADE_RETCODE_DONE)
        assertThat(unchanged.result.order).isEqualTo(42L)
    }

    @Test
    fun `a successful reply that cannot be parsed is a failure after send`() {
        val modify = responses.parseAsyncModifyResponse(42L, http(200, "<html>"))
        val close = responses.parseAsyncMutationResponse(http(200, "<html>"))

        assertThat(modify.errorMessage).startsWith("invalid gateway response after send:")
        assertThat(close.errorMessage).startsWith("invalid gateway response after send:")
        assertThat(responses.parseAsyncMutationResponse(http(502, "down")).errorMessage).isEqualTo("HTTP 502: down")
    }

    @Test
    fun `a parsed modify reply passes through`() {
        val ok = responses.parseAsyncModifyResponse(42L, http(200, """{"result":{"retcode":10009,"order":0}}"""))

        assertThat(ok.result.retcode).isEqualTo(MT5_TRADE_RETCODE_DONE)
        assertThat(ok.errorMessage).isNull()
    }
}
