package com.qkt.broker.bybit

import com.qkt.common.FixedClock
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitClientPostSignedRetryTest {
    private fun clientThatFailsTimes(
        count: Int,
        eventualBody: String,
    ): Pair<OkHttpClient, AtomicInteger> {
        val attempts = AtomicInteger()
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val n = attempts.incrementAndGet()
                    if (n <= count) {
                        throw IOException("simulated transient failure attempt $n")
                    }
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(eventualBody.toResponseBody())
                        .build()
                }.build()
        return httpClient to attempts
    }

    @Test
    fun `postSigned succeeds after two transient failures`() {
        val (httpClient, attempts) =
            clientThatFailsTimes(
                count = 2,
                eventualBody = """{"retCode":0,"retMsg":"OK","result":{}}""",
            )
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )

        val response = client.postSigned("/v5/order/create", """{"foo":"bar"}""")

        assertThat(response).contains("\"retCode\":0")
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `postSigned gives up after 3 attempts on transient failures`() {
        val (httpClient, attempts) =
            clientThatFailsTimes(
                count = 5,
                eventualBody = """{"retCode":0,"retMsg":"OK","result":{}}""",
            )
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )

        assertThatThrownBy { client.postSigned("/v5/order/create", """{"foo":"bar"}""") }
            .isInstanceOf(IOException::class.java)
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `postSigned does not retry on BybitApiException`() {
        val attempts = AtomicInteger()
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    attempts.incrementAndGet()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body("""{"retCode":10001,"retMsg":"params"}""".toResponseBody())
                        .build()
                }.build()
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )

        assertThatThrownBy { client.postSigned("/v5/order/create", """{"foo":"bar"}""") }
            .isInstanceOf(BybitApiException::class.java)
        assertThat(attempts.get()).isEqualTo(1)
    }
}
