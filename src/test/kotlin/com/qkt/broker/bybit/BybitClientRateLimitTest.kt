package com.qkt.broker.bybit

import com.qkt.common.FixedClock
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitClientRateLimitTest {
    private fun makeClient(httpClient: OkHttpClient): BybitClient =
        BybitClient(
            apiKey = "k",
            apiSecret = "s",
            testnet = true,
            httpClient = httpClient,
            clock = FixedClock(0L),
        )

    @Test
    fun `429 then 200 succeeds with sleep cap respected`() {
        val attempts = AtomicInteger()
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val n = attempts.incrementAndGet()
                    if (n == 1) {
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(429)
                            .message("Too Many Requests")
                            .header("X-Bapi-Limit-Reset-Timestamp", "100")
                            .body("""{"retCode":10006,"retMsg":"rate limit"}""".toResponseBody())
                            .build()
                    } else {
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"retCode":0,"retMsg":"OK","result":{}}""".toResponseBody())
                            .build()
                    }
                }.build()
        val client = makeClient(httpClient)

        val response = client.postSigned("/v5/order/create", """{}""")

        assertThat(response).contains("\"retCode\":0")
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `429 with reset beyond cap throws BybitRateLimitException`() {
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("X-Bapi-Limit-Reset-Timestamp", "10000")
                        .body("""{"retCode":10006,"retMsg":"rate limit"}""".toResponseBody())
                        .build()
                }.build()
        val client = makeClient(httpClient)

        assertThatThrownBy { client.postSigned("/v5/order/create", """{}""") }
            .isInstanceOf(BybitRateLimitException::class.java)
    }

    @Test
    fun `persistent 429 after one retry throws BybitRateLimitException`() {
        val httpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("X-Bapi-Limit-Reset-Timestamp", "10")
                        .body("""{"retCode":10006,"retMsg":"rate limit"}""".toResponseBody())
                        .build()
                }.build()
        val client = makeClient(httpClient)

        assertThatThrownBy { client.postSigned("/v5/order/create", """{}""") }
            .isInstanceOf(BybitRateLimitException::class.java)
    }

    @Test
    fun `accountType defaults to UNIFIED`() {
        val httpClient = OkHttpClient.Builder().build()
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )

        assertThat(client.accountType).isEqualTo("UNIFIED")
    }

    @Test
    fun `accountType explicit constructor argument overrides env default`() {
        val httpClient = OkHttpClient.Builder().build()
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                accountType = "CONTRACT",
                httpClient = httpClient,
                clock = FixedClock(0L),
            )

        assertThat(client.accountType).isEqualTo("CONTRACT")
    }
}
