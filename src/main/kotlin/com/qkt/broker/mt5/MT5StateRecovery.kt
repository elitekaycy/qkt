package com.qkt.broker.mt5

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
) {
    private val log = LoggerFactory.getLogger(MT5StateRecovery::class.java)

    fun recover() {
        val positions = client.getPositions(magic = profile.magic)
        log.info("MT5 ${profile.name} state recovery: ${positions.size} open positions")
        for (p in positions) {
            val qktSymbol = symbol.toQkt(p.symbol)
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
                ),
            )
            if (strategyName != null) seedIfOurs(p)
        }
    }

    private fun seedIfOurs(p: MT5Position) {
        val name = strategyName ?: return
        when (val m = matchOrphan(p.comment, name)) {
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
            OrphanMatch.NotOurs -> Unit
        }
    }

    private fun syntheticOrderId(ticket: Long): String = "recovered-$ticket"
}

/** Outcome of correlating a venue-side MT5 comment against this broker's strategy. */
internal sealed interface OrphanMatch {
    /** Comment unambiguously belongs to [strategyName]. Safe to seed. */
    data object Match : OrphanMatch

    /** Comment is a strict prefix of `dsl-<strategyName>` — likely truncation cut us short. Seed with WARN. */
    data object AmbiguousTruncation : OrphanMatch

    /**
     * Comment starts with `dsl-<strategyName>-` followed by non-digits — could be a longer
     * strategy name (e.g. `ema-cross` matching an `ema-cross-v2` position). Skip + WARN.
     */
    data class AmbiguousOverlap(
        val tail: String,
    ) : OrphanMatch

    /** Comment is null, non-`dsl-`, or matches a different strategy entirely. Skip silently. */
    data object NotOurs : OrphanMatch
}

/**
 * Correlate an MT5 [comment] back to [strategyName].
 *
 * MT5 truncates comments to 16 chars. Original comments take the shape `dsl-<strategy>-<n>`
 * (or `oco:<parent>/dsl-<strategy>-<n>` for OCO legs). We strip the optional `oco:<X>/`
 * prefix, then classify the result by how it relates to `dsl-<strategyName>`.
 */
internal fun matchOrphan(
    comment: String?,
    strategyName: String,
): OrphanMatch {
    if (comment.isNullOrEmpty()) return OrphanMatch.NotOurs
    val inner = stripOcoPrefix(comment)
    if (!inner.startsWith("dsl-")) return OrphanMatch.NotOurs

    val expected = "dsl-$strategyName"

    if (inner.startsWith(expected)) {
        val tail = inner.substring(expected.length)
        return when {
            tail.isEmpty() -> OrphanMatch.Match
            tail.startsWith("-") && (tail.length == 1 || tail[1].isDigit()) -> OrphanMatch.Match
            else -> OrphanMatch.AmbiguousOverlap(tail)
        }
    }

    if (expected.startsWith(inner)) return OrphanMatch.AmbiguousTruncation

    return OrphanMatch.NotOurs
}

private fun stripOcoPrefix(comment: String): String {
    if (!comment.startsWith("oco:")) return comment
    val slash = comment.indexOf('/')
    return if (slash >= 0) comment.substring(slash + 1) else comment.substring(4)
}
