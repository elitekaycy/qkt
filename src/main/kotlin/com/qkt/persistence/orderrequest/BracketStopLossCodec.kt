package com.qkt.persistence.orderrequest

import com.qkt.execution.StopLossSpec
import java.math.BigDecimal

/**
 * Flattened [StopLossSpec] of a bracket: [type] names the spec and only that spec's fields are
 * set. The fields are copied onto the bracket's [OrderRequestDto], which is why they share the
 * order DTO's field names.
 */
internal data class BracketStopFields(
    val type: String,
    val stopPrice: String? = null,
    val trailDistance: String? = null,
    val mfeThreshold: String? = null,
    val initialDistance: String? = null,
    val steps: List<StopStepDto>? = null,
    val tightenBy: String? = null,
    val intervalMs: Long? = null,
    val floorDistance: String? = null,
)

/** Decodes the bracket stop-loss spec from the flattened fields of a bracket DTO. */
internal fun OrderRequestDto.toBracketStopLoss(): StopLossSpec =
    when (val stopType = requireNotNull(stopLossType) { "Bracket DTO missing stopLossType" }) {
        "Fixed" ->
            StopLossSpec.Fixed(
                BigDecimal(
                    requireNotNull(stopPrice) { "Fixed bracket DTO missing stopPrice" },
                ),
            )
        "ArmedTrail" ->
            StopLossSpec.ArmedTrail(
                trailDistance =
                    BigDecimal(
                        requireNotNull(trailDistance) {
                            "ArmedTrail bracket DTO missing trailDistance"
                        },
                    ),
                mfeThreshold =
                    BigDecimal(
                        requireNotNull(mfeThreshold) {
                            "ArmedTrail bracket DTO missing mfeThreshold"
                        },
                    ),
            )
        "SteppedStop" ->
            StopLossSpec.SteppedStop(
                initialDistance =
                    BigDecimal(
                        requireNotNull(initialDistance) {
                            "SteppedStop bracket DTO missing initialDistance"
                        },
                    ),
                steps =
                    requireNotNull(steps) { "SteppedStop bracket DTO missing steps" }.map {
                        StopLossSpec.Step(
                            mfeThreshold = BigDecimal(it.mfeThreshold),
                            profitDistance = BigDecimal(it.profitDistance),
                        )
                    },
            )
        "TimeTighten" ->
            StopLossSpec.TimeTighten(
                initialDistance =
                    BigDecimal(
                        requireNotNull(initialDistance) {
                            "TimeTighten bracket DTO missing initialDistance"
                        },
                    ),
                tightenBy =
                    BigDecimal(
                        requireNotNull(tightenBy) {
                            "TimeTighten bracket DTO missing tightenBy"
                        },
                    ),
                intervalMs =
                    requireNotNull(intervalMs) {
                        "TimeTighten bracket DTO missing intervalMs"
                    },
                floorDistance =
                    BigDecimal(
                        requireNotNull(floorDistance) {
                            "TimeTighten bracket DTO missing floorDistance"
                        },
                    ),
            )
        else -> error("Unknown bracket stop-loss type in persisted state: $stopType")
    }

/** Flattens a bracket stop-loss spec into the fields its bracket DTO carries. */
internal fun bracketStopFields(stop: StopLossSpec): BracketStopFields =
    when (stop) {
        is StopLossSpec.Fixed ->
            BracketStopFields(
                type = "Fixed",
                stopPrice = stop.price.toPlainString(),
            )
        is StopLossSpec.ArmedTrail ->
            BracketStopFields(
                type = "ArmedTrail",
                trailDistance = stop.trailDistance.toPlainString(),
                mfeThreshold = stop.mfeThreshold.toPlainString(),
            )
        is StopLossSpec.SteppedStop ->
            BracketStopFields(
                type = "SteppedStop",
                initialDistance = stop.initialDistance.toPlainString(),
                steps =
                    stop.steps.map {
                        StopStepDto(
                            mfeThreshold = it.mfeThreshold.toPlainString(),
                            profitDistance = it.profitDistance.toPlainString(),
                        )
                    },
            )
        is StopLossSpec.TimeTighten ->
            BracketStopFields(
                type = "TimeTighten",
                initialDistance = stop.initialDistance.toPlainString(),
                tightenBy = stop.tightenBy.toPlainString(),
                intervalMs = stop.intervalMs,
                floorDistance = stop.floorDistance.toPlainString(),
            )
    }
