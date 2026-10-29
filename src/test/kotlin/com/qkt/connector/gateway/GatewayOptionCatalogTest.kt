package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** An option root's catalog from a gateway account's own listing, whatever the account is named. */
internal class GatewayOptionCatalogTest {
    private val expired = 1_790_323_200_000L // 2026-09-25T08:00Z
    private val listed = 1_793_347_200_000L // 2026-10-30T08:00Z
    private val codes =
        listOf(
            "BTC_USDC-30OCT26-80000-P",
            "BTC_USDC-30OCT26-92500-C",
            "BTC_USDC-25SEP26-80000-C",
            "BTC_USDC-25SEP26-90000-C",
            "ETH_USDC-30OCT26-4000-C",
            "BTC_USDC-30OCT26",
        )
    private val fake =
        FakeGateway(codes).apply {
            for (code in codes.filter { it.count { c -> c == '-' } == 3 }) {
                val (underlying, day, strike, right) = code.split('-')
                instruments[code] =
                    WireInstrument(
                        code,
                        "option",
                        "USDC",
                        "1",
                        "5",
                        "0.01",
                        "0.01",
                        expiry = if (day == "25SEP26") expired else listed,
                        strike = strike,
                        right = if (right == "C") "call" else "put",
                        underlying = underlying,
                    )
            }
            instruments["BTC_USDC-30OCT26"] =
                WireInstrument("BTC_USDC-30OCT26", "future", "USDC", "1", "2.5", "0.0001", "0.0001", listed)
        }
    private val source =
        GatewayOptionCatalog(
            GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 1),
            GatewaySymbols("PAPER_DERIBIT_B:"),
            FixedClock(time = 1_790_919_000_000L), // 2026-10-02
        )

    private fun root(contractSize: String = "1") =
        OptionRoot(
            "PAPER_DERIBIT_B:BTC_USDC",
            "USDC",
            BigDecimal(contractSize),
            TickSteps(BigDecimal("5"), emptyList()),
            BigDecimal("0.01"),
            BigDecimal("0.01"),
            "btc_usdc",
        )

    @AfterEach
    fun stop() = fake.shutdown()

    @Test
    fun `every option of the root the account lists is catalogued, with the listing's strike, right and expiry`() {
        val catalog = source.build(root()) {}

        assertThat(catalog.root).isEqualTo("PAPER_DERIBIT_B:BTC_USDC")
        assertThat(catalog.contracts.map { listOf(it.symbol, it.strike, it.right, it.expiryMs) }).containsExactly(
            listOf("BTC_USDC-25SEP26-80000-C", "80000", "call", expired),
            listOf("BTC_USDC-25SEP26-90000-C", "90000", "call", expired),
            listOf("BTC_USDC-30OCT26-80000-P", "80000", "put", listed),
            listOf("BTC_USDC-30OCT26-92500-C", "92500", "call", listed),
        )
        assertThat(source.listed("PAPER_DERIBIT_B:BTC_USDC")).isEqualTo(4)
    }

    @Test
    fun `an expiry the account settled in the money prices that day's delivery`() {
        fake.venue.settlements += WireSettlement("BTC_USDC-25SEP26-80000-C", "1520.5", expired)
        fake.venue.settlements += WireSettlement("BTC_USDC-25SEP26-90000-C", "0", expired)

        val catalog = source.build(root()) {}

        assertThat(catalog.deliveryPrices).containsExactly(java.util.Map.entry("2026-09-25", "81520.5"))
    }

    @Test
    fun `a listing whose contract size disagrees with the declared root fails the build`() {
        assertThatThrownBy { source.build(root(contractSize = "0.1")) {} }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("PAPER_DERIBIT_B:BTC_USDC declares contractSize 0.1")
            .hasMessageContaining("fix instruments.yaml")
    }
}
