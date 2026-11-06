package com.qkt.app

import com.qkt.broker.VenueLeg
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.events.CostIncurred
import com.qkt.events.FUNDING_REPLAY_MS
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.events.FundingCharged
import com.qkt.events.FundingShare
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.persistence.FundingPersistence
import com.qkt.persistence.PersistedFunding
import com.qkt.positions.HoldingTimeline
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Books a venue's perpetual funding ([FundingCharged]) for the strategies that share one session: each
 * strategy holding the perpetual is charged `amount × its signed holding / basis` as a [CostIncurred] of
 * kind [FillAccountingKind.FINANCING], the kind a backtest books funding as. A long and a short on one
 * account each get their own sign, and a part of the basis no strategy here holds (another tool's
 * position) is left unbooked. A holding is what the strategy held just before the record's time, from its
 * accounted fills (what it holds now when none moved it since), so one that entered with the trade a venue
 * realized the record on, or was flat then, books none of it, and one that exited with it books its share. A venue
 * that realizes a day's funding once the position is gone charges on a basis of zero; only then do the holdings
 * strategies closed since the symbol's last funding share it, when none held it (a close is written to disk only
 * for a symbol funding was heard for, so trading anything else costs no I/O). A [FundingCharged.positionless]
 * record's basis is what every session sharing the account held then, so each books only its strategies' parts and
 * together they book the record once; on a basis of zero none is booked. What was
 * booked persists under the session's state owner, so a record heard again, from the stream or a replay
 * after a restart, is booked once, and a session books nothing funded before its first start.
 */
internal class FundingBooking(
    private val bus: EventBus,
    private val positions: StrategyPositionTracker,
    private val persistence: FundingPersistence,
    private val prices: MarketPriceProvider,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(FundingBooking::class.java)

    /** Book for [strategyIds], the strategies this pipeline runs; the first owns the persisted state. */
    fun bind(strategyIds: List<String>) {
        val owner = strategyIds.firstOrNull() ?: return
        var state =
            persistence.loadFunding(owner)
                ?: PersistedFunding(clock.now(), emptyMap()).also { persistence.saveFunding(owner, it) }
        val funded = HashSet(state.closed.keys)
        val moves = HoldingTimeline<Pair<String, String>>(FUNDING_REPLAY_MS)
        bus.subscribe<FillAccountedEvent> { a ->
            if (a.strategyId !in strategyIds) return@subscribe
            val before = a.strategyPositionBefore?.quantity ?: BigDecimal.ZERO
            val after = a.strategyPositionAfter?.quantity ?: BigDecimal.ZERO
            moves.record(a.strategyId to a.symbol, HoldingTimeline.movedAt(a), before, after)
            if (a.kind != FillAccountingKind.EXECUTION || before.signum() == 0 || after.signum() != 0) {
                return@subscribe
            }
            val held = state.closed[a.symbol].orEmpty() + (a.strategyId to before)
            state = state.copy(closed = state.closed + (a.symbol to held))
            // Only a symbol funding was heard for is a perpetual worth a write; others never pay funding.
            if (a.symbol in
                funded
            ) {
                persistence.saveFunding(owner, state.copy(closed = state.closed.filterKeys(funded::contains)))
            }
        }
        bus.subscribe<FundingCharged> { e ->
            funded += e.symbol
            if (e.fundedAtMs < state.sinceMs || e.fundingId in state.booked) return@subscribe
            val closed =
                if (e.basis.signum() == 0 && !e.positionless) state.closed[e.symbol].orEmpty() else emptyMap()
            book(e, holdings(e, strategyIds, closed, moves))
            val keepFrom = clock.now() - RETAIN_MS
            state =
                state.copy(
                    booked = state.booked.filterValues { it >= keepFrom } + (e.fundingId to e.fundedAtMs),
                    closed = state.closed - e.symbol,
                )
            persistence.saveFunding(owner, state.copy(closed = state.closed.filterKeys(funded::contains)))
        }
    }

    /**
     * What each strategy held of [e]'s symbol just before its time ([moves]; now, when none since), or, when none did,
     * [closed]: what each closed since the last funding, handed only for a venue's basis of zero (a non-zero basis
     * was held then, by others when not here).
     */
    private fun holdings(
        e: FundingCharged,
        strategyIds: List<String>,
        closed: Map<String, BigDecimal>,
        moves: HoldingTimeline<Pair<String, String>>,
    ): Map<String, BigDecimal> =
        strategyIds
            .mapNotNull { id ->
                val now = { positions.positionFor(id, e.symbol)?.quantity ?: BigDecimal.ZERO }
                moves.heldAt(id to e.symbol, e.fundedAtMs, now).takeIf { it.signum() != 0 }?.let { id to it }
            }.toMap()
            .ifEmpty { closed }

    private fun book(
        e: FundingCharged,
        held: Map<String, BigDecimal>,
    ) {
        // A positionless record's basis is the whole account's: a session's own holdings would book it once each.
        val basis =
            e.basis.takeIf { it.signum() != 0 || e.positionless }
                ?: held.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (basis.signum() == 0) {
            log.warn("funding {} of {} reaches no holding to share it by; not booked: {}", e.fundingId, e.symbol, e)
            return
        }
        val price = prices.lastPrice(e.symbol)
        val position = positions.account.positionFor(e.symbol)?.let(VenueLeg::idOf)
        val share = FundingShare(e.fundingId, e.currency, e.fundedAtMs, position)
        for ((id, quantity) in held) {
            val part = e.amount.multiply(quantity, Money.CONTEXT).divide(basis, Money.CONTEXT)
            bus.publish(
                CostIncurred(id, e.symbol, part, "funding ${e.fundingId}", price, FillAccountingKind.FINANCING, share),
            )
        }
    }

    private companion object {
        /** How long a booked record's id is kept: a day longer than a replay reaches back. */
        const val RETAIN_MS = FUNDING_REPLAY_MS + 86_400_000L
    }
}
