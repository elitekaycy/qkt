package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A persisted order missing a field its type needs is refused by name, never decoded with a guess. */
class OrderRequestDtoMissingFieldTest {
    private val gtc = TimeInForce.GTC
    private val one = BigDecimal.ONE

    private fun dto(request: OrderRequest): OrderRequestDto = requireNotNull(OrderRequestDto.fromDomain(request))

    private fun refused(
        broken: OrderRequestDto,
        message: String,
    ) {
        assertThatThrownBy { broken.toDomain() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(message)
    }

    private val armed =
        OrderRequest.ArmedTrailingStop(
            "a",
            "X",
            Side.SELL,
            one,
            BigDecimal("100"),
            BigDecimal("5"),
            BigDecimal("10"),
            gtc,
            0L,
        )
    private val stepped =
        OrderRequest.SteppedStop(
            "s",
            "X",
            Side.SELL,
            one,
            BigDecimal("100"),
            BigDecimal("50"),
            listOf(StopLossSpec.Step(BigDecimal("30"), BigDecimal.ZERO)),
            gtc,
            0L,
        )
    private val tightening =
        OrderRequest.TimeTighteningStop(
            "t",
            "X",
            Side.SELL,
            one,
            BigDecimal("100"),
            BigDecimal("60"),
            BigDecimal("10"),
            900_000L,
            BigDecimal("20"),
            gtc,
            0L,
        )
    private val touched =
        OrderRequest.IfTouched(
            id = "i",
            symbol = "X",
            side = Side.BUY,
            quantity = one,
            triggerPrice = BigDecimal("100"),
            onTrigger = TriggerType.MARKET,
            timeInForce = gtc,
            timestamp = 0L,
        )
    private val trailing =
        OrderRequest.TrailingStop(
            "tr",
            "X",
            Side.SELL,
            one,
            BigDecimal("5"),
            TrailMode.ABSOLUTE,
            gtc,
            0L,
        )
    private val trailingLimit =
        OrderRequest.TrailingStopLimit(
            "tl",
            "X",
            Side.SELL,
            one,
            BigDecimal("5"),
            TrailMode.PERCENT,
            BigDecimal("1"),
            gtc,
            0L,
        )

    @Test
    fun `every engine managed stop round trips through its persisted form`() {
        listOf(armed, stepped, tightening, touched, trailing, trailingLimit).forEach { request ->
            assertThat(dto(request).toDomain()).isEqualTo(request)
        }
    }

    @Test
    fun `an armed trailing stop missing any of its levels is refused`() {
        refused(dto(armed).copy(entryPrice = null), "ArmedTrailingStop DTO missing entryPrice")
        refused(dto(armed).copy(trailDistance = null), "ArmedTrailingStop DTO missing trailDistance")
        refused(dto(armed).copy(mfeThreshold = null), "ArmedTrailingStop DTO missing mfeThreshold")
    }

    @Test
    fun `a stepped stop missing its anchor, distance or steps is refused`() {
        refused(dto(stepped).copy(entryPrice = null), "SteppedStop DTO missing entryPrice")
        refused(dto(stepped).copy(initialDistance = null), "SteppedStop DTO missing initialDistance")
        refused(dto(stepped).copy(steps = null), "SteppedStop DTO missing steps")
    }

    @Test
    fun `a time tightening stop missing any of its schedule is refused`() {
        refused(dto(tightening).copy(entryPrice = null), "TimeTighteningStop DTO missing entryPrice")
        refused(dto(tightening).copy(initialDistance = null), "TimeTighteningStop DTO missing initialDistance")
        refused(dto(tightening).copy(tightenBy = null), "TimeTighteningStop DTO missing tightenBy")
        refused(dto(tightening).copy(intervalMs = null), "TimeTighteningStop DTO missing intervalMs")
        refused(dto(tightening).copy(floorDistance = null), "TimeTighteningStop DTO missing floorDistance")
    }

    @Test
    fun `an if-touched order missing its trigger is refused`() {
        refused(dto(touched).copy(triggerPrice = null), "IfTouched DTO missing triggerPrice")
        refused(dto(touched).copy(onTrigger = null), "IfTouched DTO missing onTrigger")
    }

    @Test
    fun `a trailing stop missing its trail is refused`() {
        refused(dto(trailing).copy(trailAmount = null), "TrailingStop DTO missing trailAmount")
        refused(dto(trailing).copy(trailMode = null), "TrailingStop DTO missing trailMode")
        refused(dto(trailingLimit).copy(trailAmount = null), "TrailingStopLimit DTO missing trailAmount")
        refused(dto(trailingLimit).copy(trailMode = null), "TrailingStopLimit DTO missing trailMode")
        refused(dto(trailingLimit).copy(limitOffset = null), "TrailingStopLimit DTO missing limitOffset")
    }
}
