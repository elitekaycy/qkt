package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BracketStopLossCodecTest {
    private val entry =
        OrderRequest.Market(
            id = "entry",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1_000L,
            strategyId = "s",
        )

    private fun bracket(stop: StopLossSpec) =
        OrderRequest.Bracket(
            id = "b",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.10"),
            entry = entry,
            takeProfit = BigDecimal("4720"),
            stopLoss = stop,
            timeInForce = TimeInForce.GTC,
            timestamp = 1_000L,
            strategyId = "s",
        )

    private fun stopDto(
        type: String?,
        fields: (OrderRequestDto) -> OrderRequestDto = { it },
    ): OrderRequestDto =
        fields(
            OrderRequestDto(
                type = "Bracket",
                id = "b",
                symbol = "XAUUSD",
                side = "BUY",
                quantity = "0.10",
                timeInForce = "GTC",
                timestamp = 1_000L,
                stopLossType = type,
            ),
        )

    @Test
    fun `armed trail stop round trips through the bracket dto`() {
        val stop = StopLossSpec.ArmedTrail(BigDecimal("3.5"), BigDecimal("2"))
        val dto = encodeBracket(bracket(stop))
        assertThat(dto.stopLossType).isEqualTo("ArmedTrail")
        assertThat(dto.trailDistance).isEqualTo("3.5")
        assertThat(dto.mfeThreshold).isEqualTo("2")
        assertThat(dto.toDomain()).isEqualTo(bracket(stop))
    }

    @Test
    fun `stepped stop round trips with every step in order`() {
        val stop =
            StopLossSpec.SteppedStop(
                initialDistance = BigDecimal("5"),
                steps =
                    listOf(
                        StopLossSpec.Step(BigDecimal("2"), BigDecimal("0")),
                        StopLossSpec.Step(BigDecimal("4"), BigDecimal("1.5")),
                    ),
            )
        val dto = encodeBracket(bracket(stop))
        assertThat(dto.steps).containsExactly(StopStepDto("2", "0"), StopStepDto("4", "1.5"))
        assertThat(dto.toDomain()).isEqualTo(bracket(stop))
    }

    @Test
    fun `time tighten stop round trips its interval and floor`() {
        val stop =
            StopLossSpec.TimeTighten(
                initialDistance = BigDecimal("6"),
                tightenBy = BigDecimal("0.5"),
                intervalMs = 60_000L,
                floorDistance = BigDecimal("2"),
            )
        val dto = encodeBracket(bracket(stop))
        assertThat(dto.intervalMs).isEqualTo(60_000L)
        assertThat(dto.floorDistance).isEqualTo("2")
        assertThat(dto.toDomain()).isEqualTo(bracket(stop))
    }

    @Test
    fun `a dto without a stop type is rejected`() {
        assertThatThrownBy { stopDto(null).toBracketStopLoss() }
            .hasMessageContaining("missing stopLossType")
    }

    @Test
    fun `an unknown stop type is rejected by name`() {
        assertThatThrownBy { stopDto("Wobble").toBracketStopLoss() }
            .hasMessageContaining("Unknown bracket stop-loss type")
            .hasMessageContaining("Wobble")
    }

    @Test
    fun `each stop type names the field it is missing`() {
        val cases =
            mapOf(
                stopDto("Fixed") to "missing stopPrice",
                stopDto("ArmedTrail") to "missing trailDistance",
                stopDto("ArmedTrail") { it.copy(trailDistance = "1") } to "missing mfeThreshold",
                stopDto("SteppedStop") to "missing initialDistance",
                stopDto("SteppedStop") { it.copy(initialDistance = "1") } to "missing steps",
                stopDto("TimeTighten") to "missing initialDistance",
                stopDto("TimeTighten") { it.copy(initialDistance = "3") } to "missing tightenBy",
                stopDto("TimeTighten") { it.copy(initialDistance = "3", tightenBy = "1") } to
                    "missing intervalMs",
                stopDto("TimeTighten") {
                    it.copy(initialDistance = "3", tightenBy = "1", intervalMs = 10L)
                } to "missing floorDistance",
            )
        cases.forEach { (dto, message) ->
            assertThatThrownBy { dto.toBracketStopLoss() }.hasMessageContaining(message)
        }
    }

    @Test
    fun `fixed stop flattens to its price only`() {
        val fields = bracketStopFields(StopLossSpec.Fixed(BigDecimal("4690.50")))
        assertThat(fields).isEqualTo(BracketStopFields(type = "Fixed", stopPrice = "4690.50"))
    }
}
