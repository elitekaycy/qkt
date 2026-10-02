package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A futures root's catalog from a gateway account's listing and settlements. */
internal class GatewayContractCatalogTest {
    private val expired = 1_790_323_200_000L // 2026-09-25T08:00Z
    private val listed = 1_793_347_200_000L // 2026-10-30T08:00Z
    private val fake =
        FakeGateway(
            listOf(
                "BTC_USDC-30OCT26",
                "BTC_USDC-25SEP26",
                "BTC_USDC-PERPETUAL",
                "BTCDVOL_USDC-30OCT26",
                "ETH_USDC-30OCT26",
                "BTC_USDC-30OCT26-80000-P",
            ),
        ).apply {
            for ((code, expiry) in mapOf(
                "BTC_USDC-30OCT26" to listed,
                "BTC_USDC-25SEP26" to expired,
                "BTCDVOL_USDC-30OCT26" to listed,
                "ETH_USDC-30OCT26" to listed,
            )) {
                instruments[code] = WireInstrument(code, "future", "USDC", "1", "2.5", "0.0001", "0.0001", expiry)
            }
            instruments["BTC_USDC-PERPETUAL"] =
                WireInstrument("BTC_USDC-PERPETUAL", "perpetual", "USDC", "1", "0.1", "0.0001", "0.0001")
        }
    private val source =
        GatewayContractCatalog(
            GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 1),
            GatewaySymbols("DERIBIT:"),
            FixedClock(time = 1_790_919_000_000L), // 2026-10-02
        )

    @AfterEach
    fun stop() = fake.shutdown()

    @Test
    fun `the root's dated contracts are listed by expiry, an expired one with its settlement price`() {
        fake.act { settle(WireSettlement("BTC_USDC-25SEP26", "65000.5", expired)) }

        val catalog = source.build("DERIBIT:BTC_USDC")

        assertThat(catalog.contracts.map { Triple(it.symbol, it.expiryMs, it.deliveryPrice) }).containsExactly(
            Triple("BTC_USDC_25SEP26", expired, "65000.5"),
            Triple("BTC_USDC_30OCT26", listed, null),
        )
    }

    @Test
    fun `a settlement the gateway cannot serve leaves the contract unpriced, with a warning`() {
        fake.failing["/v1/settlements"] = 5
        val warnings = mutableListOf<String>()

        val catalog = source.build("DERIBIT:BTC_USDC", warnings::add)

        assertThat(catalog.contracts.first { it.symbol == "BTC_USDC_25SEP26" }.deliveryPrice).isNull()
        assertThat(warnings.single()).contains("BTC_USDC-25SEP26")
    }

    @Test
    fun `a root of another account is refused`() {
        assertThatThrownBy { source.build("BINANCE_UM:BTCUSDT") }.hasMessageContaining("BINANCE_UM:BTCUSDT")
    }
}
