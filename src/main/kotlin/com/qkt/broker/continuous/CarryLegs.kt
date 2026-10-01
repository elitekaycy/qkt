package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The market legs of a roll's carries on [venue]: each named `roll:<stream>:<atMs>:<strategy>:<kind>`,
 * so a leg is the same order before and after a restart, and tracked by [legs] so the engine never sees
 * it. A leg's outcome is handed on once the venue answered, after its submit returned, never inside it.
 */
internal class CarryLegs(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val venue: ContractVenue,
    private val legs: RollLegs,
) {
    private val log = LoggerFactory.getLogger(CarryLegs::class.java)

    /** The leg [kind] (`close`, `open`) of [strategyId]'s carry in [run], moving [signed] quantity on contract [index]. */
    fun market(
        run: RollRun,
        strategyId: String,
        kind: String,
        index: Int,
        signed: BigDecimal,
    ): OrderRequest.Market {
        val chain = chainOf()
        return OrderRequest.Market(
            "${base(run, strategyId)}:$kind",
            chain.contractSymbol(index),
            if (signed.signum() > 0) Side.BUY else Side.SELL,
            signed.abs(),
            TimeInForce.GTC,
            clock.now(),
            strategyId,
        )
    }

    /** The prefix of [strategyId]'s legs in [run]. */
    fun base(
        run: RollRun,
        strategyId: String,
    ) = "roll:${chainOf().symbol}:${run.measured.atMs}:$strategyId"

    /** Sends [leg] and hands its outcome to [then] once the venue answered. */
    fun send(
        leg: OrderRequest.Market,
        then: (LegOutcome) -> Unit,
    ) {
        legs.expect(leg)
        venue.broker.submit(leg)
        legs.whenEnded(leg.id, then)
    }

    /** Waits for [leg], already at the venue (recovered after a restart), as [send] does once it is sent. */
    fun await(
        leg: OrderRequest.Market,
        then: (LegOutcome) -> Unit,
    ) {
        legs.expect(leg)
        legs.whenEnded(leg.id, then)
    }

    /** Closes the [quantity] a part-filled opening [leg] left on its contract; a failure leaves it there, logged. */
    fun unwind(
        leg: OrderRequest.Market,
        quantity: BigDecimal,
    ) {
        val side = if (leg.side == Side.BUY) Side.SELL else Side.BUY
        val unwinding =
            leg.copy(
                id = leg.id.replaceAfterLast(':', "unwind"),
                side = side,
                quantity = quantity,
                timestamp = clock.now(),
            )
        send(unwinding) {
            if (it !is LegOutcome.Filled) {
                log.error(
                    "{} on {} could not be unwound: {}; it is still held there",
                    quantity,
                    leg.symbol,
                    it,
                )
            }
        }
    }
}
