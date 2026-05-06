package com.qkt.broker.bybit

import com.qkt.common.FixedClock
import java.math.BigDecimal
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitClientBalancesTest {
    @Test
    fun `balances starts empty and updateBalances replaces snapshot atomically`() {
        val httpClient = OkHttpClient.Builder().build()
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )

        assertThat(client.balances).isEmpty()

        client.updateBalances(mapOf("BTC" to BigDecimal("0.5"), "USDT" to BigDecimal("30000")))

        assertThat(client.balances).containsOnlyKeys("BTC", "USDT")
        assertThat(client.balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))

        client.updateBalances(mapOf("ETH" to BigDecimal("1.0")))

        assertThat(client.balances).containsOnlyKeys("ETH")
    }

    @Test
    fun `balances is an immutable snapshot — mutating the source map does not affect the cache`() {
        val httpClient = OkHttpClient.Builder().build()
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )
        val source = mutableMapOf("BTC" to BigDecimal("0.5"))

        client.updateBalances(source)
        source["BTC"] = BigDecimal("999")

        assertThat(client.balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))
    }
}
