package com.qkt.broker.bybit

import com.qkt.common.FixedClock
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitClientPostSignedAsyncTest {
    private fun clientWith(httpClient: OkHttpClient): BybitClient =
        BybitClient(
            apiKey = "k",
            apiSecret = "s",
            testnet = true,
            httpClient = httpClient,
            clock = FixedClock(0L),
        )

    private fun awaitResult(block: ((Result<String>) -> Unit) -> Unit): Result<String> {
        val latch = CountDownLatch(1)
        val captured = AtomicReference<Result<String>>()
        block { result ->
            captured.set(result)
            latch.countDown()
        }
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        return captured.get()
    }

    private fun respondingWith(
        code: Int,
        body: String,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("msg")
                    .body(body.toResponseBody())
                    .build()
            }.build()

    @Test
    fun `postSignedAsync delivers the response body on a 2xx`() {
        val client = clientWith(respondingWith(200, """{"retCode":0,"retMsg":"OK","result":{"orderId":"x"}}"""))

        val result = awaitResult { cb -> client.postSignedAsync("/v5/order/create", """{"foo":"bar"}""", cb) }

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).contains("\"orderId\":\"x\"")
    }

    @Test
    fun `postSignedAsync surfaces a failure on a non-2xx`() {
        val client = clientWith(respondingWith(500, """{"error":"boom"}"""))

        val result = awaitResult { cb -> client.postSignedAsync("/v5/order/create", """{"foo":"bar"}""", cb) }

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(BybitApiException::class.java)
        assertThat(error?.message).contains("HTTP 500")
    }

    @Test
    fun `postSignedAsync surfaces a failure on a transport error`() {
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { throw IOException("connection reset") }
                .build()
        val client = clientWith(httpClient)

        val result = awaitResult { cb -> client.postSignedAsync("/v5/order/create", """{"foo":"bar"}""", cb) }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("connection reset")
    }
}
