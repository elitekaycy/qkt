package com.qkt.connector.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Bracket and trailing shapes at the edge of what the MT5 wire can carry. */
class MT5OrderTranslatorEdgesTest {
    private val profile =
        MT5DefaultProfiles.exness.copy(
            instrumentOverrides =
                mapOf(
                    "EURUSD" to
                        InstrumentSpec(
                            minVolume = BigDecimal("0.01"),
                            volumeStep = BigDecimal("0.01"),
                            pointSize = BigDecimal("0.00001"),
                            digits = 5,
                            tradeStopsLevelPoints = 0,
                        ),
                ),
        )
    private val translator = MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy))
    private val tighten = StopLossSpec.TimeTighten(BigDecimal("10"), BigDecimal("1"), 60_000L, BigDecimal("2"))

    private fun bracket(
        side: Side,
        entry: OrderRequest,
    ) = OrderRequest.Bracket(
        id = "br-1",
        symbol = "EXNESS:XAUUSD",
        side = side,
        quantity = BigDecimal("0.10"),
        entry = entry,
        takeProfit = BigDecimal("2500"),
        stopLoss = tighten,
        timeInForce = TimeInForce.GTC,
        timestamp = 1L,
    )

    private fun stopEntry(side: Side) =
        OrderRequest.Stop(
            id = "br-1-entry",
            symbol = "EXNESS:XAUUSD",
            side = side,
            quantity = BigDecimal("0.10"),
            stopPrice = BigDecimal("2450"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    @Test
    fun `a time-tightening stop starts its initial distance away from a stop entry`() {
        val buy = (translator.translate(bracket(Side.BUY, stopEntry(Side.BUY))) as MT5Translation.Single).request
        val sell = (translator.translate(bracket(Side.SELL, stopEntry(Side.SELL))) as MT5Translation.Single).request

        assertThat(buy.type).isEqualTo("BUY_STOP")
        assertThat(buy.sl).isEqualByComparingTo("2440")
        assertThat(sell.type).isEqualTo("SELL_STOP")
        assertThat(sell.sl).isEqualByComparingTo("2460")
    }

    @Test
    fun `a bracket entered by a stop limit has no MT5 form`() {
        val stopLimit =
            OrderRequest.StopLimit(
                id = "br-1-entry",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                stopPrice = BigDecimal("2450"),
                limitPrice = BigDecimal("2451"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )

        assertThatThrownBy { translator.translate(bracket(Side.BUY, stopLimit)) }
            .hasMessage("MT5 bracket entry must be Market/Stop/Limit, got StopLimit")
    }

    @Test
    fun `a trail narrower than half a point is refused as too tight`() {
        val trail =
            OrderRequest.TrailingStop(
                id = "tr-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                trailAmount = BigDecimal("0.000001"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )

        assertThatThrownBy { translator.translate(trail) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("resolves to 0 MT5 points")
    }
}
