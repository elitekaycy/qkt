package com.qkt.connector.mt5

import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5PlacementPreparationTest {
    private val spec =
        InstrumentSpec(
            minVolume = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            pointSize = BigDecimal("0.00001"),
            digits = 5,
            tradeStopsLevelPoints = 20,
        )
    private val profile =
        MT5DefaultProfiles.exness.copy(
            gatewayUrl = "http://127.0.0.1:1",
            instrumentOverrides = mapOf("EXNESS:EURUSD" to spec),
        )
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String): BigDecimal? = BigDecimal("1.10000")
        }
    private val brokerSymbol = MT5Symbol(profile.symbolPolicy).toBroker("EURUSD")
    private val prep =
        MT5PlacementPreparation(
            profile,
            MT5Client(profile.gatewayUrl, profile.serverTimeZone, retryAttempts = 0),
            prices,
            MT5Symbol(profile.symbolPolicy),
            ConcurrentHashMap(),
        )

    private fun wire(
        volume: String,
        price: String? = null,
        sl: String? = null,
    ) = MT5OrderRequest(
        symbol = brokerSymbol,
        volume = BigDecimal(volume),
        type = "BUY_LIMIT",
        price = price?.let(::BigDecimal),
        sl = sl?.let(::BigDecimal),
        magic = 1,
        comment = "t",
    )

    @Test
    fun `volume rounds down to the step and prices round to the symbol digits`() {
        val result = prep.prepareForPlacement(wire("0.137", price = "1.0500049", sl = "1.0400051"))

        val ok = result as MT5PlacementPreparation.PrepareResult.Ok
        assertThat(ok.wire.volume).isEqualByComparingTo("0.13")
        assertThat(ok.wire.price).isEqualTo(BigDecimal("1.05000"))
        assertThat(ok.wire.sl).isEqualTo(BigDecimal("1.04001"))
    }

    @Test
    fun `volume that rounds below the venue minimum is refused`() {
        val result = prep.prepareVolume(brokerSymbol, BigDecimal("0.009"))

        assertThat((result as MT5PlacementPreparation.VolumeResult.Reject).reason).contains("below venue volumeMin")
    }

    @Test
    fun `a stop inside the venue stops level is refused with the distances`() {
        val result = prep.prepareForPlacement(wire("0.10", price = "1.05000", sl = "1.04990"))

        val reason = (result as MT5PlacementPreparation.PrepareResult.Reject).reason
        assertThat(reason).contains("sl too close to entry").contains("min=0.00020")
    }

    @Test
    fun `an entry inside the stops level of the current price is refused`() {
        val result = prep.prepareForPlacement(wire("0.10", price = "1.09990"))

        val reason = (result as MT5PlacementPreparation.PrepareResult.Reject).reason
        assertThat(reason).contains("entry too close to current price")
    }
}
