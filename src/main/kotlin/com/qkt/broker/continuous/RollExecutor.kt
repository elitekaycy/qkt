package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.events.CostIncurred
import com.qkt.execution.ExitReason
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Carries one stream across a roll. Both contracts are marked at the roll's reference prices (their
 * prices at the roll instant, as measured by the history); each strategy's position is carried to the
 * new contract by [RollCarry], one strategy after another; resting orders are cancelled and re-placed on
 * the new contract at the same series level; every carried position is recorded in the [ledger] and its
 * cost published as a [CostIncurred]. None of the roll's venue orders reach the engine: its continuous
 * position did not change. A venue that answers each leg before its submit returns (the backtest's
 * exchange simulator) finishes the roll before [roll] returns, exactly as before legs could wait; a live
 * venue finishes it when the last answer arrives, and the roll is [inFlight] until then.
 *
 * When the new contract refuses a strategy's opening leg, its position is gone from the venue: it
 * gets a venue close ([ExitReason.ROLL_FAILED]) at the old leg's fill, its resting orders are
 * cancelled rather than carried, and the strategy is stopped on the stream. A roll that cannot be
 * traded — the stream skipped a whole contract, or the old contract expired before the stream traded
 * again — stops every holder the same way; the exchange then settles the expired contract. A refused
 * closing leg is a configuration fault and fails loudly. The closes and costs are handed to the roll's
 * completion, not published, so the lane records the stops before any engine handler can react to them.
 */
internal class RollExecutor(
    bus: EventBus,
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    venue: ContractVenue,
    private val contractPrices: MarketPriceTracker,
    orders: ContinuousOrderMap,
    legs: RollLegs,
    private val ledger: RollLedger,
    fills: ContractFillLog,
) {
    private val log = LoggerFactory.getLogger(RollExecutor::class.java)
    private val restingOrders = RestingOrdersAtRoll(bus, clock, venue, orders, legs)
    private val carrying = RollCarry(clock, chainOf, venue, legs, fills)

    /** The chain as it stands now: a live session extends it with each roll it measures. */
    private val chain: ContinuousChain get() = chainOf()

    /** Whether a roll's legs are still out at the venue (a live venue answers after submit returns). */
    var inFlight: Boolean = false
        private set

    /**
     * Roll from contract [fromIndex] to [toIndex], carrying [positions] (strategy to signed quantity),
     * and hand what it left behind to [done]: before this returns when the venue answers every leg at
     * once (the backtest), else when the last leg's answer arrives.
     */
    fun roll(
        fromIndex: Int,
        toIndex: Int,
        positions: Map<String, BigDecimal>,
        done: (RollOutcome) -> Unit,
    ) {
        check(!inFlight) { "${chain.symbol} cannot start a roll while one is in flight" }
        val measured = chain.rollOutOf(fromIndex)
        val from = chain.contractSymbol(fromIndex)
        val to = chain.contractSymbol(toIndex)
        val stopped = "${chain.symbol} stopped: roll $from->$to at ${Instant.ofEpochMilli(measured.atMs)} failed"
        val holders = positions.filterValues { it.signum() != 0 }
        val resting = restingOrders.pull(fromIndex)
        val untradeable = untradeable(fromIndex, toIndex)
        if (untradeable != null) {
            val reason = "$stopped ($untradeable)"
            log.error(reason)
            resting.forEach { restingOrders.cancel(it, reason) }
            done(RollOutcome(stopped = holders.mapValues { reason }, closes = emptyList(), costs = emptyList()))
            return
        }
        contractPrices.update(from, measured.prices.fromPrice)
        contractPrices.update(to, measured.prices.toPrice)
        inFlight = true
        carryFrom(RollRun(fromIndex, toIndex, measured, stopped, resting, holders.entries.toList()), 0, done)
    }

    /** Carries the run's holders from [i] on, one after another as the venue answers, then finishes. */
    private fun carryFrom(
        run: RollRun,
        i: Int,
        done: (RollOutcome) -> Unit,
    ) {
        if (i == run.holders.size) return finish(run, done)
        val (strategyId, quantity) = run.holders[i]
        carrying.carry(strategyId, quantity, run) { refusal ->
            if (refusal != null) run.failed[strategyId] = "${run.stopped} ($refusal)"
            carryFrom(run, i + 1, done)
        }
    }

    private fun finish(
        run: RollRun,
        done: (RollOutcome) -> Unit,
    ) {
        val to = chain.contractSymbol(run.toIndex)
        val space = chain.spaceFor(run.toIndex)
        for (order in run.resting) {
            when (val reason = run.failed[order.request.strategyId]) {
                null -> restingOrders.replace(order, run.toIndex, to, space)
                else -> restingOrders.cancel(order, reason)
            }
        }
        run.carried.forEach(ledger::record)
        val referencePrice = space.toContinuous(run.measured.prices.toPrice)
        val cause = "roll ${chain.contractSymbol(run.fromIndex)}->$to"
        val costs = run.carried.map { CostIncurred(it.strategyId, chain.symbol, it.cost, cause, referencePrice) }
        inFlight = false
        done(RollOutcome(stopped = run.failed, closes = run.closes, costs = costs))
    }

    /**
     * Why positions cannot be carried from [fromIndex] to [toIndex] by trading, or null when they can:
     * the stream skipped a whole contract, or the old contract expired before the stream traded again
     * (the exchange settles it).
     */
    private fun untradeable(
        fromIndex: Int,
        toIndex: Int,
    ): String? {
        if (toIndex != fromIndex + 1) return "no data for ${toIndex - fromIndex - 1} contract(s) in between"
        val expiry = chain.schedule.contracts[fromIndex].expiryMs
        if (clock.now() < expiry) return null
        val contract = chain.contractSymbol(fromIndex)
        return "$contract expired at ${Instant.ofEpochMilli(expiry)} before the stream traded again"
    }
}
