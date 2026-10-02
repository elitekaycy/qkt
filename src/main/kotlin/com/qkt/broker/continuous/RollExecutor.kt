package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.events.CostIncurred
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
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
    private val orders: ContinuousOrderMap,
    private val legs: RollLegs,
    private val ledger: RollLedger,
    fills: ContractFillLog,
    private val stops: Map<String, String>,
) {
    private val log = LoggerFactory.getLogger(RollExecutor::class.java)
    private val restingOrders = RestingOrdersAtRoll(bus, clock, chainOf, venue, orders, legs)
    private val carrying = RollCarry(clock, chainOf, venue, legs, fills, ::whenReady)

    /** The chain as it stands now: a live session extends it with each roll it measures. */
    private val chain: ContinuousChain get() = chainOf()

    /** The roll whose legs are still out at the venue (a live venue answers after submit returns), or null. */
    var run: RollRun? = null
        private set

    /** Whether a roll is in flight. */
    val inFlight: Boolean get() = run != null

    /** What a restored roll does next, held until the venue is [ready]; null when nothing is held. */
    private var held: MutableList<() -> Unit>? = null

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
        val resting = orders.on(fromIndex)
        val untradeable = chain.untradeableRoll(fromIndex, toIndex, clock.now())
        if (untradeable != null) {
            val reason = "$stopped ($untradeable)"
            log.error(reason)
            restingOrders.pull(resting)
            resting.forEach { restingOrders.cancel(it, reason) }
            done(RollOutcome(stopped = holders.mapValues { reason }, closes = emptyList(), costs = emptyList()))
            return
        }
        contractPrices.update(from, measured.prices.fromPrice)
        contractPrices.update(to, measured.prices.toPrice)
        val run = RollRun(fromIndex, toIndex, measured, stopped, resting, holders.entries.toList())
        this.run = run
        restingOrders.pull(resting)
        carryFrom(run, 0, done)
    }

    /**
     * Restores the roll [run] saved mid-flight (null when none was) and the unwinds [unwinds] still out,
     * after a restart: each restored leg is awaited again at once, so its venue answer cannot be missed,
     * while every leg the roll sends next, and its finish, wait for [ready]. [done] is the roll's
     * completion, as for [roll].
     */
    fun restore(
        run: RollRun?,
        unwinds: List<OrderRequest.Market>,
        done: (RollOutcome) -> Unit,
    ) {
        check(!inFlight) { "${chain.symbol} cannot restore a roll while one is in flight" }
        held = mutableListOf()
        unwinds.forEach(carrying::resumeUnwind)
        if (run == null) return
        contractPrices.update(chain.contractSymbol(run.fromIndex), run.measured.prices.fromPrice)
        contractPrices.update(chain.contractSymbol(run.toIndex), run.measured.prices.toPrice)
        this.run = run
        resumeFrom(run, 0, done)
    }

    /**
     * The venue confirmed the cancel of the resting order under [venueId], pulled at a roll: while that roll
     * is in flight its finish settles the order, after it the order is settled now, on contract [index].
     */
    fun pulled(
        venueId: String,
        index: Int,
    ) {
        if (inFlight) return
        whenReady {
            val order = orders.byVenueId(venueId) ?: return@whenReady
            restingOrders.settle(order, stops[order.request.strategyId], index)
        }
    }

    /** The venue is back after a restart: what a restored roll held is done now, and nothing is held again. */
    fun ready() {
        val actions = held ?: return
        held = null
        actions.forEach { it() }
    }

    /** Resumes [run] at its first holder from [i] whose carry has not ended. */
    private fun resumeFrom(
        run: RollRun,
        i: Int,
        done: (RollOutcome) -> Unit,
    ) {
        if (i == run.holders.size) return whenReady { finish(run, done) }
        when (val step = run.steps[run.holders[i].key]) {
            null -> whenReady { carryFrom(run, i, done) }
            is CarryStep.Waiting -> carrying.resume(step, run) { carryFrom(run, i + 1, done) }
            is CarryStep.Carried, is CarryStep.Stopped -> resumeFrom(run, i + 1, done)
        }
    }

    private fun whenReady(action: () -> Unit) {
        held?.add(action) ?: action()
    }

    /** Carries the run's holders from [i] on, one after another as the venue answers, then finishes. */
    private fun carryFrom(
        run: RollRun,
        i: Int,
        done: (RollOutcome) -> Unit,
    ) {
        if (i == run.holders.size) return finish(run, done)
        val (strategyId, quantity) = run.holders[i]
        carrying.start(strategyId, quantity, run) { carryFrom(run, i + 1, done) }
    }

    private fun finish(
        run: RollRun,
        done: (RollOutcome) -> Unit,
    ) {
        val to = chain.contractSymbol(run.toIndex)
        val space = chain.spaceFor(run.toIndex)
        for (pulled in run.resting) {
            // As it stands now: filled, or settled before a restart, it is gone; part-filled, it counts.
            val order = orders.byVenueId(pulled.venueId) ?: continue
            if (legs.isCancelAwaited(order.venueId)) continue
            restingOrders.settle(order, run.failed[order.request.strategyId], run.toIndex)
        }
        val carried = run.carried.map { carrying.entry(run, it) }
        carried.forEach(ledger::record)
        val referencePrice = space.toContinuous(run.measured.prices.toPrice)
        val cause = "roll ${chain.contractSymbol(run.fromIndex)}->$to"
        val costs = carried.map { CostIncurred(it.strategyId, chain.symbol, it.cost, cause, referencePrice) }
        this.run = null
        done(RollOutcome(stopped = run.failed, closes = run.closes, costs = costs))
    }
}
