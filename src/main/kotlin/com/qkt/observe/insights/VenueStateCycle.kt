package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.BrokerDeal
import com.qkt.broker.CloseReason
import com.qkt.broker.VenueDescribed

/**
 * One broker's venue truth read and offered as envelopes, once per [BrokerStatePoller] cycle on its
 * thread: `state.account`, `state.positions`, `state.orders` and, when [emitDeals], the `broker.deal`s
 * booked since the last cycle. Every connector goes through the same reads ([Broker.accountState] and the
 * three beside it) and the same envelopes, each stating its `venue` and `accountId`, and each position,
 * order and deal its strategy with how it was known (`attribution`: `order` when the connector knows the
 * order exactly, `position` by the ticket map, `comment` by the venue comment, `none`) and its
 * `instrument`. Deal history is account-wide: the first broker of each account fetches it ([dealsFetched]),
 * keyed by the account id, so profile brokers sharing one login emit each deal once. Each read re-reads
 * the last [dealGraceMs] and sends a deal once by its ticket ([DealCursor]); a failed read throws and moves
 * nothing. What qkt resolved for a position (its owner, entry risk, why it closed) comes from [fills],
 * shared by every session of the process, so every session's poller states the same facts.
 */
internal class VenueStateCycle(
    private val owner: Any,
    private val offer: (InsightsEnvelope) -> Unit,
    private val attribution: TicketAttribution,
    private val deployedIds: () -> Collection<String>,
    private val venues: InsightsVenues,
    private val fills: InsightsFillFacts?,
    private val emitDeals: Boolean,
    private val backfillDays: Long,
    private val dealGraceMs: Long,
    private val sharedDeals: SharedDealFetch,
    private val clock: () -> Long,
) {
    private val dealCursor = DealCursor(backfillDays * DAY_MS, dealGraceMs)

    /** One cycle of [broker]; false when its account could not be read, and throws when its deals could not. */
    fun poll(
        broker: Broker,
        dealsFetched: MutableSet<String>,
    ): Boolean {
        val now = clock()
        // No account snapshot means no venue to report on (paper) or an unreadable
        // gateway — skip the whole broker rather than emit positions without context.
        val read = broker.accountState() ?: return false
        val account = read.copy(venue = read.venue ?: (broker as? VenueDescribed)?.venueIdentity())
        venues.accountCurrency(account.broker, account.currency)
        offer(InsightsTranslate.stateAccount(now, account))
        val tickets = broker.positionTickets()
        val deployed = deployedIds()
        val positions =
            tickets.map { t ->
                val known = t.strategyId ?: fills?.ownerOf(t.symbol, t.ticket)
                val (strategyId, how) = ownerOf(known, t.ticket, t.comment, deployed)
                StatePosition(
                    t.ticket,
                    t.symbol,
                    t.side.name,
                    t.qty,
                    t.entryPrice,
                    t.currentPrice,
                    t.profit,
                    t.swap,
                    t.openedAt,
                    strategyId,
                    t.stopLoss,
                    t.takeProfit,
                    t.requestedStopLoss,
                    t.requestedTakeProfit,
                    t.magic,
                    t.clientOrderId,
                    facts =
                        mapOf("positionId" to t.ticket, "attribution" to how) +
                            fills?.entryFields(t.symbol, t.ticket).orEmpty() +
                            ("instrument" to venues.instrumentFields(t.symbol)),
                )
            }
        offer(InsightsTranslate.statePositions(now, account.broker, positions, account.venue))
        val orders =
            broker.pendingOrders().map { o ->
                val (strategyId, how) = ownerOf(o.strategyId, o.ticket, o.comment, deployed)
                StatePendingOrder(
                    o.ticket,
                    o.symbol,
                    o.side.name,
                    o.orderType,
                    o.qty,
                    o.price,
                    o.stopLoss,
                    o.takeProfit,
                    o.expiresAt,
                    o.createdAt,
                    o.magic,
                    o.clientOrderId,
                    strategyId,
                    facts = mapOf("attribution" to how, "instrument" to venues.instrumentFields(o.symbol)),
                )
            }
        offer(InsightsTranslate.stateOrders(now, account.broker, orders, account.venue))
        val accountKey = account.venue?.accountId ?: "${account.login}@${account.server}"
        if (emitDeals && dealsFetched.add(accountKey)) {
            val from = dealCursor.from(accountKey, now)
            // A failed read throws here: nothing below runs, so the cursor stays where it was.
            val fetched = sharedDeals.deals(owner, accountKey, from, now) { f, t -> broker.deals(f, t) }
            val unsent = fetched.filter { it.ts in from..now && !dealCursor.wasSent(accountKey, it.dealTicket) }
            for (venueDeal in unsent) {
                val d = withEngineReason(venueDeal)
                val (strategyId, how) = dealOwner(d, deployed)
                // The venue overwrites a closing deal's comment ("[tp 4332.689]"), so a close
                // fetched after a restart or during backfill has no comment to attribute by.
                // Its opening deal on the same position does; remember that owner so the
                // close that follows in this very fetch resolves through the ticket map.
                if (strategyId != null && d.entry == "IN") attribution.record(d.positionTicket, strategyId)
                // Attribution is metadata, never a gate (#1143): a close whose opening deal this
                // process never saw is still a real deal, sent with no strategy for the collector
                // to inherit from the stored opening deal on the same position.
                val facts =
                    venuePayload(account.venue) + ("attribution" to how) +
                        ("instrument" to venues.instrumentFields(d.symbol))
                offer(InsightsTranslate.brokerDeal(d, strategyId, facts, account.currency))
                dealCursor.sent(accountKey, d.dealTicket, d.ts)
            }
            dealCursor.read(accountKey, now)
        }
        // Prune only after the deal fetch: a position that closed since the last cycle
        // is already gone from [tickets], and its closing deal arrives in this same
        // cycle — pruning first would drop the owner the deal needs.
        attribution.retainAll(tickets.map { it.ticket }.toSet())
        return true
    }

    /**
     * [d] with qkt's own reason for the close it booked: a venue states SIGNAL for any close an expert
     * (qkt) sent, so a timed exit, an engine-held stop or a bot close takes the reason its fill carried.
     * A reason the venue itself decided (its SL, TP, stop-out, a person) stands.
     */
    private fun withEngineReason(d: BrokerDeal): BrokerDeal {
        if (d.entry == "IN" || (d.closeReason != null && d.closeReason != CloseReason.SIGNAL)) return d
        val position = d.positionTicket ?: return d
        return fills?.closeReason(d.symbol, position, d.ts)?.let { d.copy(closeReason = it) } ?: d
    }

    private fun dealOwner(
        d: BrokerDeal,
        deployed: Collection<String>,
    ): Pair<String?, String> {
        val position = d.positionTicket ?: d.dealTicket
        return ownerOf(d.strategyId ?: fills?.ownerOf(d.symbol, position), position, d.comment, deployed)
    }

    /** Who holds [ticket]: the connector's exact [known] owner, else the ticket map, else the comment. */
    private fun ownerOf(
        known: String?,
        ticket: String,
        comment: String?,
        deployed: Collection<String>,
    ): Pair<String?, String> {
        known?.let { return it to "order" }
        attribution.ownerOf(ticket)?.let { return it to "position" }
        attribution.fromComment(comment, deployed)?.let { return it to "comment" }
        return null to "none"
    }

    private companion object {
        const val DAY_MS: Long = 86_400_000L
    }
}
