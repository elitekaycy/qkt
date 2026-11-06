package com.qkt.connector.mt5

import com.qkt.connector.mt5.MT5PlacementPreparation.PrepareResult
import com.qkt.connector.mt5.MT5PlacementPreparation.VolumeResult
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Order volumes checked against the venue's minimum, step and maximum before they are sent. */
class MT5VolumePreparationTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val symbolMeta = mutableMapOf<String, MT5SymbolInfo>()
    private val preparation =
        MT5PlacementPreparation(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            null,
            MT5Symbol(profile.symbolPolicy),
            symbolMeta,
        )

    @AfterEach
    fun stop() = gateway.close()

    private fun rules(
        step: String,
        max: String? = null,
    ) = MT5SymbolInfo(
        ask = BigDecimal("2400.2"),
        bid = BigDecimal("2400.0"),
        digits = 2,
        point = BigDecimal("0.01"),
        tradeStopsLevel = 0,
        volumeMin = BigDecimal("0.01"),
        volumeStep = BigDecimal(step),
        contractSize = BigDecimal("100"),
        volumeMax = max?.let(::BigDecimal),
    )

    private fun wire(volume: String) =
        MT5OrderRequest(symbol = "XAUUSDm", volume = BigDecimal(volume), type = "BUY", magic = 10001, comment = "e-1")

    @Test
    fun `a symbol with no volume step keeps the volume as asked`() {
        symbolMeta["XAUUSDm"] = rules(step = "0")

        assertThat(preparation.prepareVolume("XAUUSDm", BigDecimal("0.123")))
            .isEqualTo(VolumeResult.Ok(BigDecimal("0.123")))
        val prepared = preparation.prepareForPlacement(wire("0.123")) as PrepareResult.Ok
        assertThat(prepared.wire.volume).isEqualByComparingTo("0.123")
    }

    @Test
    fun `a volume above the venue maximum is refused`() {
        symbolMeta["XAUUSDm"] = rules(step = "0.01", max = "50")

        val result = preparation.prepareVolume("XAUUSDm", BigDecimal("75"))

        assertThat(result).isEqualTo(
            VolumeResult.Reject("quantized volume above venue volumeMax for XAUUSDm (input=75)"),
        )
    }

    @Test
    fun `symbol info the gateway answers with garbage leaves the volume unrounded`() {
        gateway.on("/symbol_info/XAUUSDm", "<html>")

        val result = preparation.prepareVolume("XAUUSDm", BigDecimal("0.123"))

        assertThat(result).isEqualTo(VolumeResult.Ok(BigDecimal("0.123")))
        assertThat(symbolMeta).isEmpty()
    }

    @Test
    fun `symbol info fetched once is kept for the next order`() {
        gateway.on(
            "/symbol_info/XAUUSDm",
            """{"digits":2,"point":0.01,"volume_min":0.01,"volume_step":0.01,"trade_contract_size":100}""",
        )

        assertThat(preparation.prepareVolume("XAUUSDm", BigDecimal("0.123")))
            .isEqualTo(VolumeResult.Ok(BigDecimal("0.12")))
        preparation.prepareVolume("XAUUSDm", BigDecimal("0.5"))

        assertThat(gateway.calls("/symbol_info/XAUUSDm")).hasSize(1)
        assertThat(symbolMeta.getValue("XAUUSDm").volumeStep).isEqualByComparingTo("0.01")
    }
}
