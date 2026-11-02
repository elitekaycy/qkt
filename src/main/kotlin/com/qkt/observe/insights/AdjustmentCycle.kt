package com.qkt.observe.insights

import com.qkt.broker.AdjustmentSource
import com.qkt.broker.BrokerAdjustment
import org.slf4j.LoggerFactory

/**
 * Offers a venue account's non-trade bookings ([BrokerAdjustment]: a stock CFD's dividend adjustment, a charge, a
 * correction) as `broker.deal` envelopes with entry `ADJUSTMENT`, so a strategy's dividends and charges reach Insights
 * beside its trades. Bookings tied to no position (deposits, withdrawals, credit) are the account's, not a strategy's:
 * they are logged and never offered. They are rare, so an account is read at most once per [intervalMs], each read
 * starting [overlapMs] before the last one reached and sending each booking once ([DealCursor]).
 */
internal class AdjustmentCycle(
    private val offer: (InsightsEnvelope) -> Unit,
    backfillMs: Long,
    overlapMs: Long,
    private val intervalMs: Long = 10 * 60_000L,
) {
    private val log = LoggerFactory.getLogger(AdjustmentCycle::class.java)
    private val cursor = DealCursor(backfillMs, overlapMs)
    private val readAt = HashMap<String, Long>()

    /**
     * Reads [source] for [account] when due and offers what it has not sent; [owner] attributes a booking to a strategy.
     * A failed read is logged and retried next cycle; it never stops the trades' deal poll.
     */
    fun poll(
        source: AdjustmentSource,
        account: String,
        nowMs: Long,
        currency: String?,
        owner: (BrokerAdjustment) -> Pair<String?, String>,
    ) {
        readAt[account]?.let { if (nowMs - it < intervalMs) return }
        val from = cursor.from(account, nowMs)
        val booked =
            runCatching { source.adjustments(from, nowMs) }.getOrElse {
                log.warn("venue adjustments of {} unreadable: {}", account, it.message)
                return
            }
        readAt[account] = nowMs
        for (a in booked.filter { it.ts in from..nowMs && !cursor.wasSent(account, it.dealTicket) }) {
            if (a.accountLevel) {
                log.info(
                    "venue booking on {}: {} {} ({}), account-wide",
                    account,
                    a.kind,
                    a.amount.toPlainString(),
                    a.comment,
                )
            } else {
                val (strategyId, how) = owner(a)
                offer(envelope(a, strategyId, how, currency))
            }
            cursor.sent(account, a.dealTicket, a.ts)
        }
        cursor.read(account, nowMs)
    }

    private fun envelope(
        a: BrokerAdjustment,
        strategyId: String?,
        how: String,
        currency: String?,
    ) = InsightsEnvelope(
        id = "deal-${a.broker}-${a.dealTicket}",
        seq = 0,
        ts = a.ts,
        strategyId = strategyId,
        type = "broker.deal",
        payload =
            mapOf(
                "broker" to a.broker,
                "dealTicket" to a.dealTicket,
                "positionTicket" to a.positionTicket,
                "symbol" to a.symbol,
                "entry" to ENTRY,
                "qty" to 0,
                "price" to 0,
                "profit" to a.amount,
                "magic" to a.magic,
                "comment" to listOfNotNull(a.kind.name, a.comment).joinToString(": "),
                "ts" to a.ts,
                "strategyId" to strategyId,
                "positionId" to a.positionTicket,
                "adjustment" to a.kind.name,
                "attribution" to how,
                "currency" to currency,
            ).filterValues { it != null },
    )

    companion object {
        /** The `entry` an adjustment carries: no trade leg, so it is never counted as an open or a close. */
        const val ENTRY: String = "ADJUSTMENT"
    }
}
