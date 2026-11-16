package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Order replies whose result fields are missing, null or unparseable, and replies with no body. */
class MT5OrderResponseFieldsTest {
    private val responses = MT5OrderResponses(Json { ignoreUnknownKeys = true })
    private val blank = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = "")

    private fun http(
        code: Int,
        body: String?,
    ): Response =
        Response
            .Builder()
            .request(Request.Builder().url("http://gateway/close_position").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { body?.let { body(it.toResponseBody()) } }
            .build()

    @Test
    fun `a result reports every field the venue sent`() {
        val response =
            responses.parseOrderResponse(
                """{"result":{"retcode":10009,"order":5,"deal":6,"price":"2400.5","comment":"done","volume":"0.3"},
                "error":"warned"}""",
            )

        assertThat(response.result)
            .isEqualTo(MT5OrderResult(10009, 5, 6, BigDecimal("2400.5"), "done", BigDecimal("0.3")))
        assertThat(response.errorMessage).isEqualTo("warned")
    }

    @Test
    fun `result fields that are missing null or unparseable read as a blank result`() {
        val keys = listOf("retcode", "order", "deal", "price", "volume")
        val nulls = (keys + "comment").joinToString(",") { "\"$it\":null" }
        val junk = keys.joinToString(",") { "\"$it\":\"x\"" }

        assertThat(responses.parseOrderResponse("""{"result":{}}""")).isEqualTo(MT5OrderResponse(blank))
        assertThat(
            responses.parseOrderResponse("""{"result":{$nulls},"error":null}"""),
        ).isEqualTo(MT5OrderResponse(blank))
        assertThat(responses.parseOrderResponse("""{"result":{$junk}}""")).isEqualTo(MT5OrderResponse(blank))
    }

    @Test
    fun `a cancel reply with a null message or error is unconfirmed`() {
        val raw = """{"message":null,"error":null}"""

        val response = responses.parseCancelResponse(raw)

        assertThat(response.result).isEqualTo(blank)
        assertThat(response.errorMessage).isEqualTo("unconfirmed cancel response: $raw")
        assertThat(responses.parseCancelResponse("[1]").errorMessage).isEqualTo("invalid cancel response: [1]")
    }

    @Test
    fun `only the venue's no-changes retcode turns a refused modify into success`() {
        assertThat(responses.noChangesSuccess(7L, """{"mt5_error":{"retcode":10016}}""")).isNull()
        assertThat(responses.noChangesSuccess(7L, """{"mt5_error":{"retcode":"x"}}""")).isNull()
        assertThat(responses.noChangesSuccess(7L, """{"mt5_error":{}}""")).isNull()
        assertThat(responses.noChangesSuccess(7L, """{"mt5_error":"text"}""")).isNull()
        assertThat(responses.noChangesSuccess(7L, "{}")).isNull()
        assertThat(responses.noChangesSuccess(7L, "<html>")).isNull()
        assertThat(responses.noChangesSuccess(7L, """{"mt5_error":{"retcode":$MT5_TRADE_RETCODE_NO_CHANGES}}"""))
            .isEqualTo(MT5OrderResponse(MT5OrderResult(MT5_TRADE_RETCODE_DONE, 7, 0, BigDecimal.ZERO, "no changes")))
    }

    @Test
    fun `a reply with no body is an HTTP error when refused and a failure after send when accepted`() {
        assertThat(responses.parseAsyncModifyResponse(7L, http(500, null)).errorMessage).isEqualTo("HTTP 500: ")
        assertThat(responses.parseAsyncMutationResponse(http(500, null)).errorMessage).isEqualTo("HTTP 500: ")
        assertThat(responses.parseAsyncModifyResponse(7L, http(200, null)).errorMessage)
            .startsWith("invalid gateway response after send:")
        assertThat(responses.parseAsyncMutationResponse(http(200, null)).errorMessage)
            .startsWith("invalid gateway response after send:")
    }

    @Test
    fun `an accepted close reply is read into its result`() {
        val closed =
            responses.parseAsyncMutationResponse(
                http(200, """{"result":{"retcode":10009,"deal":77,"price":2410}}"""),
            )

        assertThat(closed.result.deal).isEqualTo(77L)
        assertThat(closed.result.price).isEqualByComparingTo("2410")
        assertThat(closed.errorMessage).isNull()
    }
}
