package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

/**
 * On-startup state reconciliation for an [MT5Broker].
 *
 * Pulls open positions from the venue (filtered by [MT5BrokerProfile.magic] so only this
 * daemon's orders are picked up) and publishes [BrokerEvent.PositionReconciled] so the
 * engine's position tracker, P&L attribution, and risk view start in sync with the venue.
 *
 * When [strategyName] is non-null, also seeds [seedOrphan] for positions whose MT5
 * comment correlates back to this broker's strategy. Without this, an orphan that closes
 * server-side fires [BrokerEvent.OrderFilled] with a synthetic id and blank `strategyId`
 * so the strategy never credits the close.
 *
 * The MT5 venue truncates order comments to 16 chars, so correlation is best-effort —
 * see [matchOrphan] for the rules and the WARN paths that surface ambiguity.
 */
class MT5StateRecovery(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
    private val strategyName: String? = null,
    private val seedOrphan: (ticket: Long, orderId: String, strategyId: String) -> Unit = { _, _, _ -> },
    /** Records recovered position metadata needed by later close accounting. */
    private val onPositionRecovered: (MT5Position) -> Unit = {},
    /**
     * Returns the names of other strategies sharing this MT5 magic — looked up at recovery
     * time, not construction time, so it sees strategies deployed after this broker was
     * built. Used by [matchOrphan] to detect prefix-overlap conflicts under shared magic
     * and refuse to seed positions that two strategies could both claim. See #154.
     */
    private val siblingsLookup: () -> List<String> = { emptyList() },
) {
    private val log = LoggerFactory.getLogger(MT5StateRecovery::class.java)

    fun recover() {
        val positions =
            client.getPositions(magic = profile.magic)
                ?: error("MT5 ${profile.name} state recovery: gateway positions read failed")
        log.info("MT5 ${profile.name} state recovery: ${positions.size} open positions")
        val siblings = if (strategyName != null) siblingsLookup() else emptyList()
        for (p in positions) {
            onPositionRecovered(p)
            val match = strategyName?.let { matchOrphan(p.comment, it, siblings) }
            // Under a shared magic every strategy's recovery sees every sibling's position. Only
            // the owner's ledger can absorb a correction, so a strategy-scoped recovery publishes
            // it for the positions it claims; a session-scoped one (no strategy) publishes all.
            if (match == null || match is OrphanMatch.Match || match is OrphanMatch.AmbiguousTruncation) {
                // Books, fills and the position poller all key by the profile-prefixed qkt symbol
                // (EXNESS:XAUUSD); the bare venue symbol never matched any of them (#1103).
                val qktSymbol = "${profile.name.uppercase()}:${symbol.toQkt(p.symbol)}"
                val signedQty = if (p.type == 0) p.volume else p.volume.negate()
                bus.publish(
                    BrokerEvent.PositionReconciled(
                        symbol = qktSymbol,
                        oldQty = null,
                        newQty = signedQty,
                        oldAvgPx = null,
                        newAvgPx = p.priceOpen,
                        source = "mt5:${profile.name}",
                        reason = "startup-recovery",
                        ticket = p.ticket.toString(),
                        strategyId = strategyName,
                    ),
                )
            }
            if (match != null) seedIfOurs(p, match)
        }
    }

    private fun seedIfOurs(
        p: MT5Position,
        m: OrphanMatch,
    ) {
        val name = strategyName ?: return
        when (m) {
            is OrphanMatch.Match -> {
                seedOrphan(p.ticket, syntheticOrderId(p.ticket), name)
                log.info(
                    "MT5 ${profile.name} seeding orphan ticket=${p.ticket} comment='${p.comment}' strategy=$name",
                )
            }
            is OrphanMatch.AmbiguousTruncation -> {
                seedOrphan(p.ticket, syntheticOrderId(p.ticket), name)
                log.warn(
                    "MT5 ${profile.name} seeding orphan ticket=${p.ticket} comment='${p.comment}' strategy=$name " +
                        "by truncated prefix; if another strategy with a prefix-overlapping name shares this " +
                        "magic, close attribution may be wrong. Keep strategy names prefix-disjoint per magic.",
                )
            }
            is OrphanMatch.AmbiguousOverlap -> {
                log.warn(
                    "MT5 ${profile.name} skipping orphan ticket=${p.ticket} comment='${p.comment}': matches " +
                        "this strategy's prefix '$name' but tail '${m.tail}' suggests a longer strategy name. " +
                        "Close will fire with blank strategyId.",
                )
            }
            is OrphanMatch.ConflictWithSibling -> {
                log.warn(
                    "MT5 ${profile.name} skipping orphan ticket=${p.ticket} comment='${p.comment}': " +
                        "truncated prefix matches both this strategy '$name' and sibling(s) " +
                        "${m.siblings.joinToString(",")} on the same magic. Close will fire with blank " +
                        "strategyId. Rename one of the strategies so their `dsl-<name>` prefixes diverge " +
                        "within MT5's 16-char comment window.",
                )
            }
            OrphanMatch.NotOurs -> Unit
        }
    }

    private fun syntheticOrderId(ticket: Long): String = "recovered-$ticket"
}
