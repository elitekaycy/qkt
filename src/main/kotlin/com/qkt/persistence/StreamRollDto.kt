package com.qkt.persistence

import com.qkt.execution.OrderRequest
import com.qkt.persistence.orderrequest.OrderRequestDto
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** On-disk shape of a [PersistedStreamRoll]. */
@Serializable
internal data class StreamRollDto(
    val from: String,
    val to: String,
    val atMs: Long,
    val fromPrice: String,
    val toPrice: String,
    val stopped: String,
    val resting: List<StreamOrderDto>,
    val holders: List<RollHolderDto>,
    val steps: List<CarryStepDto>,
) {
    fun toDomain() =
        PersistedStreamRoll(
            from,
            to,
            atMs,
            BigDecimal(fromPrice),
            BigDecimal(toPrice),
            stopped,
            resting.map { it.toDomain() },
            holders.map { PersistedRollHolder(it.strategyId, BigDecimal(it.quantity)) },
            steps.map { it.toDomain() },
        )

    companion object {
        fun of(r: PersistedStreamRoll) =
            StreamRollDto(
                r.from,
                r.to,
                r.atMs,
                r.fromPrice.toPlainString(),
                r.toPrice.toPlainString(),
                r.stopped,
                r.resting.map(StreamOrderDto::of),
                r.holders.map { RollHolderDto(it.strategyId, it.quantity.toPlainString()) },
                r.steps.map(CarryStepDto::of),
            )
    }
}

@Serializable
internal data class RollHolderDto(
    val strategyId: String,
    val quantity: String,
)

/** A roll leg as saved: a market order (every leg of a lane saved before legs were collared), or a collared limit. */
private fun rollLeg(
    dto: OrderRequestDto,
    what: String,
): OrderRequest =
    dto.toDomain().also {
        check(it is OrderRequest.Market || it is OrderRequest.Limit) { "$what is neither a market nor a limit order" }
    }

/** On-disk shape of a [PersistedRollLeg]. */
@Serializable
internal data class RollLegDto(
    val leg: OrderRequestDto,
    val slices: List<OrderFilledDto>,
    val earlier: List<OrderFilledDto> = emptyList(),
) {
    fun toDomain() =
        PersistedRollLeg(
            rollLeg(leg, "roll leg ${leg.id}"),
            slices.map { it.toDomain() },
            earlier.map { it.toDomain() },
        )

    companion object {
        fun of(l: PersistedRollLeg) =
            RollLegDto(
                StreamOrderDto.encodeRequest(l.leg),
                l.slices.map(OrderFilledDto::of),
                l.earlier.map(OrderFilledDto::of),
            )
    }
}

/** On-disk shape of a [PersistedCarryStep]: [kind] names the step and only that step's fields are set. */
@Serializable
internal data class CarryStepDto(
    val kind: String,
    val strategyId: String,
    val quantity: String,
    val leg: OrderRequestDto? = null,
    val close: OrderFilledDto? = null,
    val open: OrderFilledDto? = null,
    val reason: String? = null,
) {
    fun toDomain(): PersistedCarryStep {
        val q = BigDecimal(quantity)
        return when (kind) {
            CLOSING -> PersistedCarryStep.Closing(strategyId, q, leg())
            OPENING -> PersistedCarryStep.Opening(strategyId, q, required(close).toDomain(), leg())
            CARRIED -> PersistedCarryStep.Carried(strategyId, q, required(close).toDomain(), required(open).toDomain())
            STOPPED -> PersistedCarryStep.Stopped(strategyId, q, required(reason), close?.toDomain())
            else -> error("unknown carry step in persisted state: $kind")
        }
    }

    private fun leg(): OrderRequest = rollLeg(required(leg), "$kind step of $strategyId")

    private fun <T : Any> required(value: T?): T =
        requireNotNull(value) { "$kind step of $strategyId is missing a field" }

    companion object {
        private const val CLOSING = "closing"
        private const val OPENING = "opening"
        private const val CARRIED = "carried"
        private const val STOPPED = "stopped"

        fun of(s: PersistedCarryStep): CarryStepDto {
            val q = s.quantity.toPlainString()
            return when (s) {
                is PersistedCarryStep.Closing ->
                    CarryStepDto(
                        CLOSING,
                        s.strategyId,
                        q,
                        leg = StreamOrderDto.encodeRequest(s.leg),
                    )
                is PersistedCarryStep.Opening ->
                    CarryStepDto(
                        OPENING,
                        s.strategyId,
                        q,
                        leg = StreamOrderDto.encodeRequest(s.leg),
                        close = OrderFilledDto.of(s.close),
                    )
                is PersistedCarryStep.Carried ->
                    CarryStepDto(
                        CARRIED,
                        s.strategyId,
                        q,
                        close = OrderFilledDto.of(s.close),
                        open = OrderFilledDto.of(s.open),
                    )
                is PersistedCarryStep.Stopped ->
                    CarryStepDto(STOPPED, s.strategyId, q, reason = s.reason, close = s.close?.let(OrderFilledDto::of))
            }
        }
    }
}
