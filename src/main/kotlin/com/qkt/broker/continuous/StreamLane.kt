package com.qkt.broker.continuous

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.instrument.PriceAdjustment
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.time.Instant

/**
 * One continuous stream's translation to its contracts. The stream's venue runs on a private bus
 * and a private contract price view; everything it publishes belongs to this stream and is
 * republished on [bus] in continuous space: the engine's order id, the continuous symbol, and
 * prices mapped by the contract the order worked on. The lane keeps each strategy's position on the
 * stream from those fills and rolls it ([RollExecutor]) when the schedule moves to the next contract;
 * a strategy whose position could not be carried places no further orders on the stream. A live lane
 * with a [store] saves its state ([LaneState]) before every venue action and after every venue answer,
 * and a restart restores it from there.
 */
internal class StreamLane(
    private val bus: EventBus,
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    ledger: RollLedger,
    private val fills: ContractFillLog,
    venueFactory: (EventBus, MarketPriceTracker, PositionProvider) -> ContractVenue,
    private val store: LaneStateStore? = null,
) {
    /** The chain as it stands now: a live session extends it with each roll it measures. */
    private val chain: ContinuousChain get() = chainOf()

    private val venueBus = EventBus(clock, MonotonicSequenceGenerator())
    private val contractPrices = MarketPriceTracker()

    /** The stream's contract positions as its venue account holds them: every venue fill, roll legs too, netted. */
    private val book = StrategyPositionTracker(clock = clock::now)
    private val venue =
        venueFactory(venueBus, contractPrices, book.account).let { if (store == null) it else it.savingFirst(::save) }
    private val orders = ContinuousOrderMap()
    private val legs = RollLegs()
    private val positions = LinkedHashMap<String, BigDecimal>()
    private val stops = LinkedHashMap<String, String>()
    private val rolls = RollExecutor(bus, clock, chainOf, venue, contractPrices, orders, legs, ledger, fills, stops)
    private val state = LaneState(chainOf, positions, stops, orders, book, legs, rolls)
    private val recovery = LaneRecovery(clock, chainOf, venue, orders, legs, rolls, book, ::space)
    private var current: Int? = null
    private val spaces = HashMap<Int, PriceSpace>()

    init {
        LaneVenueEvents(
            bus,
            clock,
            chainOf,
            venueBus,
            book,
            orders,
            legs,
            positions,
            fills,
            ::space,
            ::save,
        ) { venueId ->
            current?.let { rolls.pulled(venueId, it) }
        }
        if (store != null) venueBus.subscribeAll { if (it is BrokerEvent) save() }
        store?.load(chain.symbol)?.let { saved ->
            val restored = state.restore(saved, clock.now())
            current = restored.contractIndex
            rolls.restore(restored.run, restored.unwinds, ::applyRoll)
        }
    }

    /** Has the venue take back the lane's orders after a restart, beside the engine's [engineOrders]; see [LaneRecovery]. */
    fun recover(engineOrders: List<ManagedOrder>): Set<String> = recovery.recover(engineOrders).also { save() }

    /** The session is restored: the venue is told so and the lane goes on ([LaneRecovery.ready]). */
    fun ready() {
        recovery.ready()
        save()
    }

    /** Whether the engine order [engineId] works on this stream. */
    fun owns(engineId: String): Boolean = orders.byEngineId(engineId) != null

    fun submit(request: OrderRequest): SubmitAck {
        val now = clock.now()
        refusal(now)?.let { return reject(request, it) }
        val index = requireNotNull(catchUp(now))
        stops[request.strategyId]?.let { return reject(request, it) }
        val space =
            try {
                space(index)
            } catch (e: IllegalArgumentException) {
                return reject(request, e.message ?: "${chain.symbol} cannot price contract $index")
            }
        val translated =
            toContract(request, request.id, chain.contractSymbol(index), space)
                ?: return reject(request, "${chain.symbol} does not accept ${request::class.simpleName} orders")
        orders.add(ContinuousOrder(request, request.id, index))
        return venue.broker.submit(translated)
    }

    fun cancel(engineId: String) {
        val order = orders.byEngineId(engineId) ?: return
        if (legs.isCancelAwaited(order.venueId)) {
            // The roll's cancel is already out: once confirmed, the order is cancelled instead of re-placed.
            orders.add(order.copy(cancelRequested = true))
            save()
            return
        }
        venue.broker.cancel(order.venueId)
    }

    /** Stops the stream's venue. */
    fun shutdown() = venue.broker.shutdown()

    /** Roll if the schedule has moved on, then hand the contract tick behind [tick] to the venue. */
    fun onTick(tick: Tick) {
        val index = catchUp(tick.timestamp) ?: return
        val space = space(index)
        val contractTick =
            tick.copy(
                symbol = chain.contractSymbol(index),
                price = space.priceToContract(tick.price),
                bid = tick.bid?.let(space::priceToContract),
                ask = tick.ask?.let(space::priceToContract),
            )
        contractPrices.update(contractTick)
        venue.onTick(contractTick)
    }

    /** The contract followed at [nowMs], after rolling onto it if the lane was still on an earlier one. */
    private fun catchUp(nowMs: Long): Int? {
        val index = chain.indexAt(nowMs) ?: return null
        val previous = current
        if (previous == index) return index
        // One roll at a time: while one is in flight (after a restart, possibly long), the next waits for it.
        if (rolls.inFlight) return null
        current = index
        if (previous == null) {
            save()
            return index
        }
        rolls.roll(previous, index, positions, ::applyRoll)
        return index
    }

    /** Applies what a roll left behind, then tells the engine of the closes and costs. */
    private fun applyRoll(outcome: RollOutcome) {
        stops.putAll(outcome.stopped)
        outcome.closes.forEach { positions.merge(it.strategyId, it.signedQuantity(), BigDecimal::add) }
        // Saved before the engine hears of it: a crash in between cannot publish the roll's closes twice.
        save()
        outcome.closes.forEach(bus::publish)
        outcome.costs.forEach(bus::publish)
    }

    /** Saves the lane as it stands, when it has a [store]: before every venue action and after every venue answer. */
    private fun save() {
        store?.save(state.snapshot(current))
    }

    /** Contract [index]'s price mapping, built once per contract. */
    private fun space(index: Int): PriceSpace = spaces.getOrPut(index) { chain.spaceFor(index) }

    private fun refusal(now: Long): String? {
        val stream = chain.symbol
        if (chain.adjust != PriceAdjustment.PANAMA) {
            return "continuous futures orders need adjust: panama; $stream uses ${chain.adjust.name.lowercase()}"
        }
        if (now < chain.servedFromMs) return "$stream is served from ${Instant.ofEpochMilli(chain.servedFromMs)}"
        if (chain.indexAt(now) == null) return "$stream has no contract at ${Instant.ofEpochMilli(now)}"
        if (rolls.inFlight) return "$stream is rolling to its next contract; resend once the roll is done"
        return null
    }

    private fun reject(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        bus.publish(BrokerEvent.OrderRejected(request.id, null, reason, request.strategyId, clock.now()))
        return SubmitAck(request.id, null, accepted = false, rejectReason = reason)
    }
}
