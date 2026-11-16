package com.qkt.connector.gateway

import com.qkt.broker.VenueInstrument
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** What a gateway's listing states about each symbol: ticks, instruments and a spot pair's coin. */
class GatewaySymbolsListingTest {
    private fun listed(
        code: String,
        kind: String,
        currency: String,
        tick: String = "0.5",
        contract: String = "1",
    ) = WireInstrument(code, kind, currency, contract, tick, "0.01", "0.01")

    @Test
    fun `two codes that share a qkt symbol are refused`() {
        assertThatThrownBy { GatewaySymbols("V:").update(listOf("BTC-USD", "BTC_USD")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("gateway codes collide once '-' is written '_': [BTC-USD, BTC_USD]")
    }

    @Test
    fun `a listing gives each symbol its instrument and only a positive tick`() {
        val symbols = GatewaySymbols("V:")
        symbols.updateListing(
            listOf(
                listed("BTC-PERP", "perpetual", "USD", tick = "0.5", contract = "10"),
                listed("ETH-PERP", "perpetual", "USD", tick = "0", contract = "0"),
                listed("SOL-PERP", "perpetual", "USD", tick = "n/a", contract = "-1"),
            ),
        )

        assertThat(symbols.tick("V:BTC_PERP")).isEqualByComparingTo("0.5")
        assertThat(symbols.tick("V:ETH_PERP")).isNull()
        assertThat(symbols.tick("V:SOL_PERP")).isNull()
        assertThat(symbols.instrument("V:BTC_PERP"))
            .isEqualTo(VenueInstrument("BTC-PERP", "perpetual", BigDecimal("10"), BigDecimal("0.5"), "USD", null))
        assertThat(
            symbols.instrument("V:ETH_PERP"),
        ).isEqualTo(VenueInstrument("ETH-PERP", "perpetual", null, null, "USD", null))
        assertThat(symbols.instrument("V:SOL_PERP")!!.contractSize).isNull()
        assertThat(symbols.instrument("V:DOGE_PERP")).isNull()
    }

    @Test
    fun `only a spot pair that ends in its currency names the coin it buys`() {
        val symbols = GatewaySymbols("V:")
        symbols.updateListing(
            listOf(
                listed("BTCUSD", "spot", "USD"),
                listed("BTC_USDC", "spot", "USDC"),
                listed("ETH-USDT", "spot", "USDT"),
                listed("USD", "spot", "USD"),
                listed("_USD", "spot", "USD"),
                listed("BTCEUR", "spot", "USD"),
                listed("SOLUSD", "perpetual", "USD"),
            ),
        )

        assertThat(symbols.baseAsset("V:BTCUSD")).isEqualTo("BTC")
        assertThat(symbols.baseAsset("V:BTC_USDC")).isEqualTo("BTC")
        assertThat(symbols.baseAsset("V:ETH_USDT")).isEqualTo("ETH")
        assertThat(symbols.baseAsset("V:USD")).`as`("the currency alone is no pair").isNull()
        assertThat(symbols.baseAsset("V:_USD")).`as`("a separator alone is no coin").isNull()
        assertThat(symbols.baseAsset("V:BTCEUR")).isNull()
        assertThat(symbols.baseAsset("V:SOLUSD")).isNull()
        assertThat(symbols.baseAsset("V:UNLISTED")).isNull()
        assertThat(symbols.instrument("V:BTCUSD")!!.baseAsset).isEqualTo("BTC")
    }
}
