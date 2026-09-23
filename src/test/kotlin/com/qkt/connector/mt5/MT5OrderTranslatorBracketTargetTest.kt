package com.qkt.connector.mt5

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A market bracket's relative take profit is re-resolved from the fill and attached by position
 * modify, so its pre-fill placeholder never goes on the wire: the gateway validates the placeholder
 * against the live ask when it executes, and a fast market moves the ask past a small distance
 * between fire and send (live run 003: 8 of 10 stack legs refused).
 */
class MT5OrderTranslatorBracketTargetTest {
    private val profile =
        MT5DefaultProfiles.exness.copy(
            instrumentOverrides =
                mapOf(
                    "EXNESS:XAUUSD" to
                        InstrumentSpec(
                            minVolume = BigDecimal("0.01"),
                            volumeStep = BigDecimal("0.01"),
                            pointSize = BigDecimal("0.001"),
                            digits = 3,
                            tradeStopsLevelPoints = 0,
                        ),
                ),
        )
    private val translator = MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy))

    @Test
    fun `relative take profit is left off the wire while the stop placeholder still ships`() {
        val wire = wireOf(ChildBy(NumLit(BigDecimal("0.10"))))

        assertThat(wire.tp).isNull()
        assertThat(wire.sl).isEqualByComparingTo("4277.039")
    }

    @Test
    fun `absolute take profit still ships with the entry`() {
        val wire = wireOf(ChildAt(NumLit(BigDecimal("4292.139"))))

        assertThat(wire.tp).isEqualByComparingTo("4292.139")
        assertThat(wire.sl).isEqualByComparingTo("4277.039")
    }

    @Test
    fun `a bracket built without an expression keeps its take profit on the wire`() {
        val wire = wireOf(null)

        assertThat(wire.tp).isEqualByComparingTo("4292.139")
    }

    private fun wireOf(takeProfitAst: ChildPriceAst?): MT5OrderRequest {
        val bracket =
            OrderRequest.Bracket(
                id = "leg",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.05"),
                entry =
                    OrderRequest.Market(
                        id = "leg-entry",
                        symbol = "EXNESS:XAUUSD",
                        side = Side.BUY,
                        quantity = Money.of("0.05"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                    ),
                takeProfit = Money.of("4292.139"),
                stopLoss = StopLossSpec.Fixed(Money.of("4277.039")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                takeProfitAst = takeProfitAst,
                stopLossAst = ChildBy(NumLit(BigDecimal("15"))),
            )
        return (translator.translate(bracket) as MT5Translation.Single).request
    }
}
