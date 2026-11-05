package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Looks at the quotes before a holder's legs go. A far or missing quote is often gone a moment later, so it is looked at
 * again on a later tick, up to [RollLegIds.MAX_ATTEMPTS] looks [RollLegIds.RETRY_SPACING_MS] apart and clear of the old
 * contract's expiry; only then is it the reason the holder is stopped. No band: nothing to look at, at once.
 *
 * When the lane [hearsOldContract] (its live feed reads the old contract for the roll, [LaneRollTap]) and no quote of
 * it was heard yet, as after a restart past the roll instant, the legs wait for one up to [OLD_QUOTE_LOOKS] looks, so
 * the closing leg is collared around the old contract's own quote; one still silent then goes on the estimate from the
 * new contract's quote ([RollRun.fromReference]).
 */
internal class RollQuoteWait(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val refusals: RollRefusals,
    private val retries: RollLegRetries,
    private val hearsOldContract: Boolean = false,
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
        val symbol = chainOf().symbol
        val why = refusals.ofQuotes(quantity, run.measured, run.quotes)
        if (why == null) {
            if (!awaitsOldQuote(run, look)) return carry(next)
            log.warn("{} roll for {} waits for a quote of {}, look {}", symbol, strategyId, from(run), look)
            return again(run, strategyId, quantity, carry, next, look)
        }
        if (look >= RollLegIds.MAX_ATTEMPTS || !chainOf().clearForRetry(run.fromIndex, clock.now())) {
            log.error("{} not carried for {}: {}", symbol, strategyId, why)
            run.steps[strategyId] = CarryStep.Stopped(strategyId, quantity, why, null)
            return next()
        }
        log.warn("{} roll for {} waits, look {}: {}", symbol, strategyId, look, why)
        again(run, strategyId, quantity, carry, next, look)
    }

    /** Whether the legs wait for the old contract's first quote, which its feed is bringing. */
    private fun awaitsOldQuote(
        run: RollRun,
        look: Int,
    ) = hearsOldContract &&
        run.quotes.from == null &&
        look < OLD_QUOTE_LOOKS &&
        chainOf().clearForRetry(run.fromIndex, clock.now())

    private fun from(run: RollRun) = chainOf().contractSymbol(run.fromIndex)

    private fun again(
        run: RollRun,
        strategyId: String,
        quantity: BigDecimal,
        carry: (() -> Unit) -> Unit,
        next: () -> Unit,
        look: Int,
    ) = retries.after(clock.now() + RollLegIds.RETRY_SPACING_MS) {
        run.quotes = run.quotes.later()
        look(run, strategyId, quantity, carry, next, look + 1)
    }

    private companion object {
        /** Looks at most for the old contract's first quote: one wait of [RollLegIds.RETRY_SPACING_MS]. */
        const val OLD_QUOTE_LOOKS = 2
    }
}
