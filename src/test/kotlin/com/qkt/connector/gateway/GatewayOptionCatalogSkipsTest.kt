package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connector.mt5.Mt5RoutedGateway
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Listings a catalog cannot use are skipped and reported; settlements that cannot be read price nothing. */
class GatewayOptionCatalogSkipsTest {
    private val expired = 1_790_323_200_000L // 2026-09-25T08:00Z
    private val gateway = Mt5RoutedGateway()
    private val warnings = mutableListOf<String>()
    private val source =
        GatewayOptionCatalog(
            GatewayClient(gateway.url, "k", httpTimeoutMs = 500, retryAttempts = 1),
            GatewaySymbols("PAPER_DERIBIT_B:"),
            FixedClock(time = 1_790_919_000_000L), // 2026-10-02
        )
    private val root =
        OptionRoot(
            "PAPER_DERIBIT_B:BTC_USDC",
            "USDC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("5"), emptyList()),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )

    @AfterEach
    fun stop() = gateway.close()

    private fun option(
        code: String,
        currency: String = "USDC",
        strike: String? = "80000",
        right: String? = "call",
    ) = WireInstrument(
        code,
        "option",
        currency,
        "1",
        "5",
        "0.01",
        "0.01",
        expiry = expired,
        strike = strike,
        right = right,
        underlying = "BTC_USDC",
    )

    private fun lists(vararg options: WireInstrument) =
        gateway.on(
            "/v1/instruments",
            Json.encodeToString(ListSerializer(WireInstrument.serializer()), options.toList()),
        )

    @Test
    fun `an option priced in another currency is skipped and reported`() {
        lists(option("BTC_USDC-25SEP26-80000-C", currency = "BTC"))

        val catalog = source.build(root) { warnings.add(it) }

        assertThat(catalog.contracts).isEmpty()
        assertThat(warnings.single())
            .isEqualTo("skipped BTC_USDC-25SEP26-80000-C: priced in BTC; PAPER_DERIBIT_B:BTC_USDC trades USDC")
    }

    @Test
    fun `an option with no strike, an odd right, or a name off the option form is skipped and reported`() {
        lists(
            option("BTC_USDC-25SEP26-80000-C", strike = null),
            option("BTC_USDC-25SEP26-90000-C", right = "straddle"),
            option("BTC_USDC-SEP-ODD"),
        )

        val catalog = source.build(root) { warnings.add(it) }

        assertThat(catalog.contracts).isEmpty()
        assertThat(warnings)
            .hasSize(3)
            .allMatch { it.endsWith("listed without a usable strike, right, expiry or option name") }
    }

    @Test
    fun `settlements the gateway fails to serve leave the delivery prices empty`() {
        lists(option("BTC_USDC-25SEP26-80000-C"))
        gateway.respond("/v1/settlements", 500 to """{"error":{"code":"internal","message":"down"}}""")

        val catalog = source.build(root) { warnings.add(it) }

        assertThat(catalog.contracts.map { it.symbol }).containsExactly("BTC_USDC-25SEP26-80000-C")
        assertThat(catalog.deliveryPrices).isEmpty()
        assertThat(warnings.single()).startsWith("settlements unavailable (internal: down)")
    }

    @Test
    fun `settlements the gateway cannot reach leave the delivery prices empty`() {
        lists(option("BTC_USDC-25SEP26-80000-C"))
        gateway.respond("/v1/settlements", 503 to """{"error":{"code":"venue_unavailable","message":"down"}}""")

        val catalog = source.build(root) { warnings.add(it) }

        assertThat(catalog.deliveryPrices).isEmpty()
        assertThat(warnings.single()).startsWith("settlements unavailable (/v1/settlements")
    }
}
