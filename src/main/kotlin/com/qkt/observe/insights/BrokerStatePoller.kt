package com.qkt.observe.insights

import com.qkt.broker.Broker
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Polls each broker's account state, open position tickets, and deal history on its own
 * daemon thread and offers the resulting envelopes to the insights sink. Never runs on
 * the engine thread — a slow or dead gateway delays only this poller. Restart-safe:
 * deal envelope ids are deterministic, so re-backfilling [backfillDays] of history
 * after a restart dedupes at the collector instead of double-counting.
 *
 * e.g. one cycle for EXNESS emits one "state.account", one "state.positions" (full
 * replace), one "state.orders" (full replace), and — when the DEAL family is enabled —
 * one "broker.deal" per locally-attributed deal booked since the previous cycle. Pollers
 * without deployed strategy ids keep account-level behavior and emit all deals.
 */
class BrokerStatePoller(
    private val brokers: List<Broker>,
    private val sink: InsightsSink,
    /** Ticket → strategy mirror maintained by the session; this poller only reads it. */
    private val attribution: TicketAttribution,
    /** Currently-deployed strategy ids, for comment-prefix fallback attribution. */
    private val deployedIds: () -> Collection<String>,
    private val pollIntervalMs: Long = 10_000L,
    /** How far back the first cycle fetches deals; later cycles fetch only new ones. */
    private val backfillDays: Long = 30L,
    /**
     * Whether deal history is emitted. Gated by the DEAL insights family — the poller
     * itself rides STATE, so a config enabling state but not deal gets account,
     * positions, and pending orders without the deal stream.
     */
    private val emitDeals: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(BrokerStatePoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Newest deal timestamp seen per broker; the next fetch starts just after it. */
    private val lastDealTs = mutableMapOf<Broker, Long>()
    private val consecutiveFailures = mutableMapOf<Broker, Int>()

    /** Starts the polling thread. Idempotent — a second call is a no-op. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread =
            Thread({
                while (running.get()) {
                    pollOnce()
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "qkt-broker-state").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * One full pass over every broker. A broker fault is logged once per
     * consecutive-failure streak and never kills the thread.
     */
    internal fun pollOnce() {
        for (broker in brokers) {
            runCatching { pollBroker(broker) }
                .onSuccess { consecutiveFailures[broker] = 0 }
                .onFailure { e ->
                    val failures = (consecutiveFailures[broker] ?: 0) + 1
                    consecutiveFailures[broker] = failures
                    if (failures == 1) {
                        log.warn("[insights] broker state poll failed for {}: {}", broker.name, e.message)
                    }
                }
        }
    }

    private fun pollBroker(broker: Broker) {
        val now = clock()
        // No account snapshot means no venue to report on (paper) or an unreadable
        // gateway — skip the whole broker rather than emit positions without context.
        val account = broker.accountState() ?: return
        sink.offer(InsightsTranslate.stateAccount(now, account))

        val tickets = broker.positionTickets()
        val deployed = deployedIds()
        sink.offer(
            InsightsTranslate.statePositions(
                ts = now,
                broker = account.broker,
                positions =
                    tickets.map { t ->
                        StatePosition(
                            ticket = t.ticket,
                            symbol = t.symbol,
                            side = t.side.name,
                            qty = t.qty,
                            entryPrice = t.entryPrice,
                            currentPrice = t.currentPrice,
                            profit = t.profit,
                            swap = t.swap,
                            openedAt = t.openedAt,
                            strategyId = attribution.ownerOf(t.ticket) ?: attribution.fromComment(t.comment, deployed),
                            stopLoss = t.stopLoss,
                            takeProfit = t.takeProfit,
                            requestedStopLoss = t.requestedStopLoss,
                            requestedTakeProfit = t.requestedTakeProfit,
                            magic = t.magic,
                            clientOrderId = t.clientOrderId,
                        )
                    },
            ),
        )

        sink.offer(
            InsightsTranslate.stateOrders(
                ts = now,
                broker = account.broker,
                orders =
                    broker.pendingOrders().map { o ->
                        StatePendingOrder(
                            ticket = o.ticket,
                            symbol = o.symbol,
                            side = o.side.name,
                            orderType = o.orderType,
                            qty = o.qty,
                            price = o.price,
                            stopLoss = o.stopLoss,
                            takeProfit = o.takeProfit,
                            expiresAt = o.expiresAt,
                            createdAt = o.createdAt,
                            magic = o.magic,
                            clientOrderId = o.clientOrderId,
                            strategyId = attribution.ownerOf(o.ticket) ?: attribution.fromComment(o.comment, deployed),
                        )
                    },
            ),
        )

        if (emitDeals) {
            var newest = lastDealTs.getOrPut(broker) { now - backfillDays * DAY_MS }
            val from = newest + 1
            for (d in broker.deals(from, now).filter { it.ts in from..now }) {
                val strategyId =
                    attribution.ownerOf(d.positionTicket ?: d.dealTicket)
                        ?: attribution.fromComment(d.comment, deployed)
                if (deployed.isEmpty() || strategyId in deployed) {
                    sink.offer(InsightsTranslate.brokerDeal(d, strategyId))
                }
                if (d.ts > newest) newest = d.ts
            }
            lastDealTs[broker] = newest
        }

        // Prune only after the deal fetch: a position that closed since the last cycle
        // is already gone from [tickets], and its closing deal arrives in this same
        // cycle — pruning first would drop the owner the deal needs. The venue
        // overwrites SL/TP close comments ("[tp 4332.689]"), so the map is the only
        // attribution source for those.
        attribution.retainAll(tickets.map { it.ticket }.toSet())
    }

    /** Stops the polling thread, waiting up to two seconds for the current cycle. */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(2_000)
        thread = null
    }

    private companion object {
        const val DAY_MS: Long = 86_400_000L
    }
}
