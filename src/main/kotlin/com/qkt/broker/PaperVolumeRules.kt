package com.qkt.broker

import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import java.math.RoundingMode

/**
 * The venue volume rules [PaperBroker] applies to an order before it accepts it: the quantity rounds
 * down to the instrument's `volumeStep`, and an order whose rounded volume is zero, below `volumeMin`
 * or above `volumeMax` is refused with the venue's reason. Live venues ([com.qkt.connector.mt5.MT5PlacementPreparation])
 * and `mt5-sim` ([MT5BrokerSimulator]) refuse the same orders, so a paper run never books a fill the
 * venue would not take. A symbol the registry does not know passes through unchanged.
 */
internal class PaperVolumeRules(
    private val instruments: InstrumentRegistry,
) {
    sealed interface Outcome {
        /** The order with its volume on the venue grid. */
        data class Sized(
            val request: OrderRequest,
        ) : Outcome

        /** The venue would refuse the order; [reason] is what the rejection carries. */
        data class Refused(
            val reason: String,
        ) : Outcome
    }

    fun apply(request: OrderRequest): Outcome {
        val meta = instruments.lookup(request.symbol) ?: return Outcome.Sized(request)
        val quantized =
            request.quantity
                .divide(meta.volumeStep, 0, RoundingMode.DOWN)
                .multiply(meta.volumeStep)
        val volumeMax = meta.volumeMax
        return when {
            quantized.signum() == 0 || quantized < meta.volumeMin ->
                Outcome.Refused(
                    "quantized volume $quantized below venue volumeMin ${meta.volumeMin} for ${request.symbol}",
                )
            volumeMax != null && quantized > volumeMax ->
                Outcome.Refused("quantized volume $quantized above venue volumeMax $volumeMax for ${request.symbol}")
            else -> Outcome.Sized(request.withVolume(quantized))
        }
    }
}
