package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Looks at the quotes before a holder's legs go. A far or missing quote is often gone a moment later, so it is looked at
 * again on a later tick, up to [RollLegIds.MAX_ATTEMPTS] looks [RollLegIds.RETRY_SPACING_MS] apart and clear of the old
 * contract's expiry; only then is it the reason the holder is stopped. No band: nothing to look at, at once.
 */
internal class RollQuoteWait(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val refusals: RollRefusals,
    private val retries: RollLegRetries,
) {
    private val log = LoggerFactory.getLogger(RollQuoteWait::class.java)

    /** Has [carry] carry [strategyId]'s signed [quantity] in [run] once clear, or stops it; [next] goes on after either. */
    fun look(
        run: RollRun,
        strategyId: String,
        quantity: BigDecimal,
        carry: (() -> Unit) -> Unit,
        next: () -> Unit,
    ) = look(run, strategyId, quantity, carry, next, 1)

    private fun look(
        run: RollRun,
        strategyId: String,
        quantity: BigDecimal,
        carry: (() -> Unit) -> Unit,
        next: () -> Unit,
        look: Int,
    ) {
        val why = refusals.ofQuotes(quantity, run.measured, run.quotes) ?: return carry(next)
        val symbol = chainOf().symbol
        if (look >= RollLegIds.MAX_ATTEMPTS || !chainOf().clearForRetry(run.fromIndex, clock.now())) {
            log.error("{} not carried for {}: {}", symbol, strategyId, why)
            run.steps[strategyId] = CarryStep.Stopped(strategyId, quantity, why, null)
            return next()
        }
        log.warn("{} roll for {} waits, look {}: {}", symbol, strategyId, look, why)
        retries.after(clock.now() + RollLegIds.RETRY_SPACING_MS) {
            run.quotes = run.quotes.later()
            look(run, strategyId, quantity, carry, next, look + 1)
        }
    }
}
