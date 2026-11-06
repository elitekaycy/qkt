package com.qkt.persistence.orderrequest

import com.qkt.persistence.LegIntentDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The on-disk DTOs keep every field through JSON, whether it holds its default or not. */
class OrderRequestDtoSerializationTest {
    private val compact = Json { ignoreUnknownKeys = true }
    private val verbose = Json { encodeDefaults = true }

    private fun <T> roundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        for (json in listOf(compact, verbose)) {
            assertThat(json.decodeFromString(serializer, json.encodeToString(serializer, value))).isEqualTo(value)
        }
    }

    private val entry = FillAnchorExprDto("Entry")
    private val num = FillAnchorExprDto("NumLit", value = "2")

    private val bare = OrderRequestDto("Market", "o", "X", "BUY", "1", "GTC", 1L)

    private val everyField =
        OrderRequestDto(
            type = "Bracket",
            id = "b",
            symbol = "X",
            side = "SELL",
            quantity = "0.5",
            timeInForce = "GTD",
            timestamp = 9L,
            strategyId = "s",
            closesTicket = "77",
            closesLegId = "leg-1",
            partialClose = true,
            limitPrice = "1",
            stopPrice = "2",
            triggerPrice = "3",
            onTrigger = "MARKET",
            expiresAt = 10L,
            entryPrice = "4",
            trailDistance = "5",
            mfeThreshold = "6",
            initialDistance = "7",
            steps = listOf(StopStepDto("1", "0")),
            tightenBy = "0.5",
            intervalMs = 60_000L,
            floorDistance = "1",
            trailAmount = "2",
            trailMode = "PCT",
            limitOffset = "0.1",
            entry = bare,
            parent = bare,
            children = listOf(bare),
            takeProfit = "8",
            stopLossType = "Fixed",
            takeProfitAst = ChildPriceAstDto("At", num),
            stopLossAst = ChildPriceAstDto("ArmedTrail", num, entry),
            scaleOutLegs = listOf(ScaleOutLegDto("9", "0.5")),
            legIntent =
                LegIntentDto(
                    "Open",
                    legId = "leg-2",
                    role = "PRIMARY",
                    parentLegId = "leg-1",
                    ticket = "t",
                    partial = true,
                ),
        )

    @Test
    fun `an order dto with every field set or none set survives json`() {
        roundTrips(OrderRequestDto.serializer(), everyField)
        roundTrips(OrderRequestDto.serializer(), bare)
    }

    @Test
    fun `fill anchor and child price dtos survive json with and without optional parts`() {
        roundTrips(FillAnchorExprDto.serializer(), FillAnchorExprDto("BinaryOp", op = "ADD", lhs = entry, rhs = num))
        roundTrips(FillAnchorExprDto.serializer(), entry)
        roundTrips(ChildPriceAstDto.serializer(), ChildPriceAstDto("By", num))
        roundTrips(ChildPriceAstDto.serializer(), ChildPriceAstDto("ArmedTrail", num, entry))
        roundTrips(LegIntentDto.serializer(), LegIntentDto("Net"))
    }

    @Test
    fun `missing optional fields decode to their defaults`() {
        val decoded =
            compact.decodeFromString(
                OrderRequestDto.serializer(),
                """{"type":"Market","id":"o","symbol":"X","side":"BUY","quantity":"1","timeInForce":"GTC","timestamp":1}""",
            )

        assertThat(decoded).isEqualTo(bare)
        assertThat(decoded.strategyId).isEmpty()
        assertThat(decoded.partialClose).isFalse
    }
}
