package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Polls open MT5 positions and emits reconciliation events when they drift from local state.
 *
 * MT5 lacks a push notification for position changes, so the broker polls. The poller
 * compares the venue's open positions against its last snapshot and publishes
 * [com.qkt.events.BrokerEvent.PositionReconciled] for any difference.
 */
class MT5PositionPoller(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
    private val clock: Clock,
    /**
     * Invoked when a position appears in the venue snapshot that wasn't there last tick.
     *
     * Phase 26c: the broker uses this to correlate a venue ticket back to a qkt-side
     * pending order id and emit the matching [com.qkt.events.BrokerEvent.OrderFilled].
     * Default `null` keeps existing test fixtures backward-compatible.
     */
    private val onPositionOpened: ((MT5Position) -> Boolean)? = null,
    /**
     * Ledger legs pinned to this broker's tickets. A leg whose ticket is absent from two
     * consecutive clean snapshots, and that this poller never saw open, closed while nobody
     * was watching (a restart gap, a gateway outage that outlived the position): its venue
     * close is synthesized from deal history so the ledger retires the leg through the
     * ordinary fill path (#1097). Two snapshots, not one, so a leg booked from a fill that
     * raced a snapshot already in flight is never mistaken for a vanished position.
     */
    private val bookedLegs: (() -> List<com.qkt.broker.BookedLeg>)? = null,
    /**
     * Invoked when an existing position's venue volume increases. Entry orders can fill in
     * multiple slices while retaining one ticket; the broker uses this hook to advance the
     * order's cumulative fill without waiting for a new position ticket.
     */
    private val onPositionIncreased: ((MT5Position, MT5Position) -> Unit)? = null,
    /**
     * Resolves a closed position ticket to the qkt-side (clientOrderId, strategyId)
     * pair so the synthesized [BrokerEvent.OrderFilled] flows through the per-strategy
     * filters in [com.qkt.app.TradingPipeline]. Returns null for positions opened
     * outside of this qkt session (manual user trades, another instance with the same
     * magic, pre-session positions before [MT5StateRecovery] runs).
     */
    private val closedTicketMeta: ((Long) -> ClosedPositionMeta?)? = null,
    /** Releases qkt-side attribution after a ticket is fully closed. */
    private val onPositionClosed: ((Long) -> Unit)? = null,
    /** Returns true for an engine-requested SL/TP change that should not alert. */
    private val isExpectedProtectionChange: ((BrokerEvent.PositionProtectionChanged) -> Boolean)? = null,
    /**
     * Reports whether qkt's close-by-ticket request is absent, still pending, or confirmed.
     * Pending closes are deferred without consuming the marker; confirmed closes are skipped
     * because their fill was already published by the submission callback.
     */
    private val engineCloseState: ((Long) -> EngineCloseState)? = null,
    /** Deduplicates venue costs shared with engine-initiated close callbacks. */
    private val venueCostsForClose: ((Long, List<MT5Deal>, Boolean) -> BigDecimal)? = null,
    /**
     * Best-effort source for the close price. The position snapshot diff only tells
     * us *that* a ticket closed, not at what price — the venue has already discarded
     * the position by the time we observe it gone. The latest market tick is the
     * closest proxy available without a separate deal-history API call.
     */
    private val priceProvider: MarketPriceProvider? = null,
    /**
     * Session gate: when non-null, [tick] skips the venue HTTP call whenever it returns false
     * (out of session). Null keeps the legacy always-on behavior. [MT5Broker] wires this to the
     * profile's [SymbolCalendars] so a multi-asset broker polls whenever any asset class is open.
     */
    private val sessionGate: ((Instant) -> Boolean)? = null,
    /**
     * Invoked once when [GATEWAY_FAILURE_ALERT_THRESHOLD] consecutive polls fail —
     * the operator-alert hook. The poller keeps skipping diffs until a clean read.
     */
    private val onGatewayUnreachable: ((Int) -> Unit)? = null,
    /** Invoked after an outage threshold was crossed and a clean read succeeds. */
    private val onGatewayRecovered: ((Int) -> Unit)? = null,
    /**
     * Invoked once per in-session poll round, on the poller thread, after the position diff.
     * [MT5Broker] uses it to keep the margin-level cache warm so the risk engine's approve
     * path reads a cached value instead of paying a gateway round-trip on the engine thread.
     * Must not throw; exceptions are caught and logged.
     */
    private val onPollRound: (() -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(MT5PositionPoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastSnapshot: Map<Long, MT5Position> = emptyMap()
    private var consecutiveFailures: Int = 0

    /**
     * Tickets for which a close has already been published, mapped to the time it was
     * observed. MT5 never reuses a ticket number across positions, so once a ticket is
     * gone it cannot legitimately re-open — any later re-appearance in `/positions` is a
     * snapshot flicker. Ignoring these tickets stops the poller from re-diffing a flicker
     * into a phantom re-open and a duplicate close (which, after the meta was consumed,
     * surfaced as a blank-strategy "opened outside this session" event). Reaped after
     * [CLOSED_TICKET_RETENTION_MULTIPLIER] × [MT5BrokerProfile.pollIntervalMs] purely for
     * memory hygiene — far longer than any plausible snapshot hiccup.
     */
    private val closedTickets: MutableMap<Long, Long> = ConcurrentHashMap()

    /** Booked tickets absent from the latest clean snapshot(s), with the consecutive miss count. */
    private val missingBooked = HashMap<Long, Int>()

    /** Runtime-opened tickets rejected by this broker instance's local correlation. */
    private val foreignRuntimeTickets: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val observedClosingDeals: MutableMap<Long, MutableSet<Long>> = mutableMapOf()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastSnapshot = client.getPositions(magic = profile.magic)?.associateBy { it.ticket } ?: emptyMap()
        thread =
            Thread({
                while (running.get()) {
                    try {
                        Thread.sleep(profile.pollIntervalMs)
                        if (!running.get()) break
                        tick()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: java.io.IOException) {
                        // Message-only: gateway unreachability is expected between retries;
                        // the stack adds no signal and floods test output (#879).
                        log.warn("MT5 poller for ${profile.name} tick failed: ${e.message}")
                    } catch (e: Exception) {
                        log.warn("MT5 poller for ${profile.name} tick failed", e)
                    }
                }
            }, "qkt-mt5-poller-${profile.name}").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(5000)
        thread = null
    }

    /** True after this poller has already emitted the ticket's venue-close fill. */
    internal fun hasPublishedClose(ticket: Long): Boolean = ticket in closedTickets

    internal fun tick() {
        if (sessionGate != null && !sessionGate(Instant.ofEpochMilli(clock.now()))) {
            return
        }
        onPollRound?.let { hook ->
            try {
                hook()
            } catch (e: Exception) {
                log.warn("MT5 poller for {} onPollRound hook threw: {}", profile.name, e.message)
            }
        }
        val now = clock.now()
        closedTickets.entries.removeIf { now - it.value >= profile.pollIntervalMs * CLOSED_TICKET_RETENTION_MULTIPLIER }
        // A failed read means UNKNOWN, not "everything closed" — diffing an outage
        // snapshot would synthesize a close fill for every open position (#359).
        val snapshot =
            client.getPositions(magic = profile.magic) ?: run {
                consecutiveFailures++
                if (consecutiveFailures == GATEWAY_FAILURE_ALERT_THRESHOLD) {
                    log.error(
                        "MT5 poller for {} cannot reach the gateway ({} consecutive failures) — " +
                            "position diffs suspended until a clean read",
                        profile.name,
                        consecutiveFailures,
                    )
                    onGatewayUnreachable?.invoke(consecutiveFailures)
                }
                return
            }
        if (consecutiveFailures >= GATEWAY_FAILURE_ALERT_THRESHOLD) {
            log.info("MT5 poller for {} gateway recovered after {} failures", profile.name, consecutiveFailures)
            onGatewayRecovered?.invoke(consecutiveFailures)
        }
        consecutiveFailures = 0
        // A ticket we've already reported closed cannot legitimately reappear, so drop any
        // flicker that re-surfaces it before diffing — see [closedTickets].
        val current =
            snapshot
                .filter { it.ticket !in closedTickets }
                .associateByTo(mutableMapOf()) { it.ticket }
        for (ticket in lastSnapshot.keys.intersect(current.keys)) {
            val previous = lastSnapshot[ticket] ?: continue
            val latest = current[ticket] ?: continue
            if (previous.sl.compareTo(latest.sl) != 0 || previous.tp.compareTo(latest.tp) != 0) {
                if (isForeignWithoutOwner(ticket)) continue
                val event =
                    BrokerEvent.PositionProtectionChanged(
                        broker = profile.name,
                        symbol = "${profile.name.uppercase()}:${symbol.toQkt(latest.symbol)}",
                        ticket = ticket.toString(),
                        oldStopLoss = previous.sl,
                        newStopLoss = latest.sl,
                        oldTakeProfit = previous.tp,
                        newTakeProfit = latest.tp,
                        strategyId = closedTicketMeta?.invoke(ticket)?.strategyId ?: "",
                        timestamp = now,
                    )
                if (isExpectedProtectionChange?.invoke(event) != true) {
                    log.error(
                        "MT5 position protection changed broker={} ticket={} sl={}->{} tp={}->{}",
                        profile.name,
                        ticket,
                        previous.sl,
                        latest.sl,
                        previous.tp,
                        latest.tp,
                    )
                    bus.publish(event)
                }
            }
            if (latest.volume < previous.volume) {
                if (isForeignWithoutOwner(ticket)) continue
                when (engineCloseState?.invoke(ticket) ?: EngineCloseState.NONE) {
                    EngineCloseState.PENDING -> {
                        current[ticket] = previous
                        continue
                    }
                    EngineCloseState.CONFIRMED -> continue
                    EngineCloseState.NONE ->
                        publishClose(
                            previous,
                            previous.volume - latest.volume,
                            ticket,
                            now,
                            positionClosed = false,
                        )
                }
            } else if (latest.volume > previous.volume) {
                onPositionIncreased?.invoke(previous, latest)
            }
        }
        retireVanishedBookedLegs(current, now)
        val closed = lastSnapshot.keys - current.keys
        for (ticket in closed) {
            when (engineCloseState?.invoke(ticket) ?: EngineCloseState.NONE) {
                EngineCloseState.PENDING -> {
                    lastSnapshot[ticket]?.let { current[ticket] = it }
                    continue
                }
                EngineCloseState.CONFIRMED -> {
                    closedTickets[ticket] = now
                    continue
                }
                EngineCloseState.NONE -> closedTickets[ticket] = now
            }
            val p = lastSnapshot[ticket] ?: continue
            if (!isForeignWithoutOwner(ticket)) {
                publishClose(p, p.volume, ticket, now, positionClosed = true)
            }
            foreignRuntimeTickets.remove(ticket)
            observedClosingDeals.remove(ticket)
            onPositionClosed?.invoke(ticket)
        }
        val opened = current.keys - lastSnapshot.keys
        for (ticket in opened) {
            val p = current[ticket] ?: continue
            if (onPositionOpened?.invoke(p) == false) foreignRuntimeTickets.add(ticket)
        }
        lastSnapshot = current
    }

    private fun isForeignWithoutOwner(ticket: Long): Boolean =
        ticket in foreignRuntimeTickets && closedTicketMeta?.invoke(ticket) == null

    private fun publishClose(
        position: MT5Position,
        quantity: java.math.BigDecimal,
        ticket: Long,
        now: Long,
        positionClosed: Boolean,
    ) = publishCloseFill(
        qktSymbol = "${profile.name.uppercase()}:${symbol.toQkt(position.symbol)}",
        closeSide = if (position.type == 0) Side.SELL else Side.BUY,
        quantity = quantity,
        ticket = ticket,
        now = now,
        dealsFromUtcMs = position.openTime,
        fallbackPrice = position.priceOpen,
        positionClosed = positionClosed,
        strategyId = null,
    )

    /**
     * Book the venue close of every ledger leg whose ticket the venue no longer holds. See
     * [bookedLegs] for the two-snapshot rule; an engine close still pending on the ticket is
     * left to its own callback.
     */
    private fun retireVanishedBookedLegs(
        current: Map<Long, MT5Position>,
        now: Long,
    ) {
        val legs = bookedLegs?.invoke() ?: return
        if (legs.isEmpty()) {
            missingBooked.clear()
            return
        }
        val stillMissing = HashSet<Long>(legs.size)
        for (leg in legs) {
            val ticket = leg.ticket.toLongOrNull() ?: continue
            if (ticket in current || ticket in lastSnapshot || ticket in closedTickets) continue
            if (engineCloseState?.invoke(ticket) == EngineCloseState.PENDING) continue
            stillMissing.add(ticket)
            val misses = (missingBooked[ticket] ?: 0) + 1
            if (misses < BOOKED_LEG_MISSES_TO_RETIRE) {
                missingBooked[ticket] = misses
                continue
            }
            closedTickets[ticket] = now
            log.error(
                "MT5 poller for {} found ledger leg {} ({}) on ticket {} that the venue no longer holds — " +
                    "booking the missed venue close",
                profile.name,
                leg.legId,
                leg.strategyId,
                ticket,
            )
            publishCloseFill(
                qktSymbol = leg.symbol,
                closeSide = if (leg.side == Side.BUY) Side.SELL else Side.BUY,
                quantity = leg.quantity,
                ticket = ticket,
                now = now,
                dealsFromUtcMs = leg.openedAt - 1L,
                fallbackPrice = leg.entryPrice,
                positionClosed = true,
                strategyId = leg.strategyId,
            )
            observedClosingDeals.remove(ticket)
            onPositionClosed?.invoke(ticket)
        }
        missingBooked.keys.retainAll(stillMissing)
        missingBooked.keys.removeAll(closedTickets.keys)
    }

    private fun publishCloseFill(
        qktSymbol: String,
        closeSide: Side,
        quantity: java.math.BigDecimal,
        ticket: Long,
        now: Long,
        dealsFromUtcMs: Long,
        fallbackPrice: BigDecimal,
        positionClosed: Boolean,
        /** Owner when the ticket has no qkt-side meta (a leg restored before this session). */
        strategyId: String?,
    ) {
        val meta = closedTicketMeta?.invoke(ticket)
        val clientOrderId =
            meta?.clientOrderId
                ?: "mt5-close-$ticket".also {
                    log.warn(
                        "MT5 poller for {} saw ticket {} close with no qkt-side meta — using synthetic attribution",
                        profile.name,
                        ticket,
                    )
                }
        val deal = client.getClosingDeal(ticket, fromUtcMs = dealsFromUtcMs, toUtcMs = now)
        val seenDeals = observedClosingDeals.getOrPut(ticket) { mutableSetOf() }
        val newDeals = deal?.deals.orEmpty().filter { seenDeals.add(it.ticket) }
        val newClosingDeals = newDeals.filter { it.entry != 0 && it.volume.signum() > 0 }
        val venueCosts =
            venueCostsForClose?.invoke(ticket, deal?.deals.orEmpty(), positionClosed)
                ?: costsForDeals(newDeals)
                ?: BigDecimal.ZERO
        val closePrice =
            closingPrice(newClosingDeals)
                ?: deal?.price
                ?: (priceProvider?.lastPrice(qktSymbol) ?: fallbackPrice).also { fallback ->
                    log.warn(
                        "MT5 poller for {} pricing close of ticket {} from local proxy {} — closing deal unavailable",
                        profile.name,
                        ticket,
                        fallback.toPlainString(),
                    )
                }
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = clientOrderId,
                brokerOrderId = ticket.toString(),
                symbol = qktSymbol,
                side = closeSide,
                price = closePrice,
                quantity = quantity,
                strategyId = meta?.strategyId ?: strategyId ?: "",
                timestamp = now,
                updatesOrderExecution = false,
                venueCosts = venueCosts,
                exitReason = closingDealExitReason(newClosingDeals),
            ),
        )
    }

    private fun closingPrice(deals: List<MT5Deal>): BigDecimal? {
        if (deals.isEmpty()) return null
        val volume = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        if (volume.signum() == 0) return null
        val notional = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.price.multiply(deal.volume) }
        return notional.divide(volume, com.qkt.common.Money.CONTEXT)
    }

    private fun costsForDeals(deals: List<MT5Deal>): BigDecimal? =
        deals
            .takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO) { total, deal ->
                total - deal.commission - deal.swap - deal.fee
            }

    private companion object {
        /** Multiples of the poll interval to retain a closed ticket before reaping. */
        const val CLOSED_TICKET_RETENTION_MULTIPLIER: Long = 100L

        /** Consecutive failed polls before [onGatewayUnreachable] fires. */
        const val GATEWAY_FAILURE_ALERT_THRESHOLD: Int = 3

        /** Consecutive clean snapshots a booked ticket must be absent from before its leg retires. */
        const val BOOKED_LEG_MISSES_TO_RETIRE: Int = 2
    }
}

/** Submission state used to disambiguate an engine close from a venue-side close. */
enum class EngineCloseState { NONE, PENDING, CONFIRMED }

/**
 * Meta the poller needs to publish a useful close [BrokerEvent.OrderFilled] when a
 * ticket disappears from the venue snapshot. Populated by [MT5Broker] as positions
 * open (either synchronously from a Market/Bracket fill, or asynchronously when a
 * pending order transitions to a position).
 */
data class ClosedPositionMeta(
    val clientOrderId: String,
    val strategyId: String,
)
