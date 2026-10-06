package com.qkt.connector.mt5

import com.qkt.common.Clock
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Reads a part-filled entry's position from deal history when a snapshot alone can hide what
 * happened: the rest of the order filling and the position closing within one poll (#1354). E.g.
 * position 9001 seen at 0.04 of 0.10 is gone on the next poll; its deals show 0.04 + 0.06 in and
 * 0.10 out, so the entry's last 0.06 is booked here and the poller books a 0.10 close, not 0.04.
 * The entry lots booked here that no snapshot showed are handed to the poller once, through
 * [MT5PartialEntries.takeUnseenGrowth].
 */
internal class MT5PartialEntryHistory(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val clock: Clock,
    private val partialEntries: MT5PartialEntries,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val unreadable = ConcurrentHashMap<Long, Int>()

    enum class Replay {
        /** The ticket carries no part-filled entry: the snapshot is all there is to read. */
        NOT_PARTIAL,

        /**
         * History unreadable, or not yet matching the venue's open volume: ask again. After
         * [UNREADABLE_LIMIT] such answers in a row the snapshot is trusted instead ([NOT_PARTIAL]).
         */
        UNKNOWN,

        /** The entry now holds every opening deal of the position. */
        REPLAYED,
    }

    /** Brings the entry on [positionTicket] up to its opening deals, checked against [openVolume] at the venue now. */
    fun replay(
        positionTicket: Long,
        openVolume: BigDecimal,
    ): Replay {
        if (!partialEntries.isPartialEntry(positionTicket)) return Replay.NOT_PARTIAL
        val now = clock.now()
        val deals =
            client.getPositionDeals(positionTicket, now - HISTORY_LOOKBACK_MS, now)
                ?: return unreadable(positionTicket)
        val opening = deals.filter { it.entry == 0 && it.volume.signum() > 0 && it.price.signum() > 0 }
        val closing = deals.filter { it.entry != 0 && it.volume.signum() > 0 }
        val opened = opening.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        val closed = closing.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        val price = MT5UnknownOutcomeMatching.weightedDealPrice(opening)
        if (price == null || (opened - closed).compareTo(openVolume) != 0) {
            log.warn(
                "MT5Broker {} ticket {} history shows {} in and {} out but {} open",
                profile.name,
                positionTicket,
                opened.toPlainString(),
                closed.toPlainString(),
                openVolume.toPlainString(),
            )
            return unreadable(positionTicket)
        }
        unreadable.remove(positionTicket)
        partialEntries.advanceFromHistory(positionTicket, opened, price, opening.minOf { it.timeMs })
        return Replay.REPLAYED
    }

    private fun unreadable(positionTicket: Long): Replay {
        if ((unreadable.merge(positionTicket, 1, Int::plus) ?: 1) < UNREADABLE_LIMIT) return Replay.UNKNOWN
        unreadable.remove(positionTicket)
        log.error(
            "MT5Broker {} ticket {} part-filled entry: deal history unusable {} times in a row; booking from " +
                "the position snapshot, which can miss an entry slice and its close — reconcile from venue deals",
            profile.name,
            positionTicket,
            UNREADABLE_LIMIT,
        )
        return Replay.NOT_PARTIAL
    }

    /**
     * For the position poller: the entry lots booked beyond its snapshot of [positionTicket], after
     * replaying history when [openVolume] (the venue's volume now, zero when gone) is given; null
     * when that history cannot be trusted yet.
     */
    fun growthBeside(
        positionTicket: Long,
        openVolume: BigDecimal?,
    ): BigDecimal? {
        if (openVolume != null && replay(positionTicket, openVolume) == Replay.UNKNOWN) return null
        return partialEntries.takeUnseenGrowth(positionTicket)
    }

    private companion object {
        /** How far back the position's deals are searched; covers a GTC order resting for a month. */
        const val HISTORY_LOOKBACK_MS: Long = 31L * 24 * 60 * 60 * 1000

        /** Unusable history answers in a row, per ticket, before the snapshot is trusted instead. */
        const val UNREADABLE_LIMIT: Int = 5
    }
}
