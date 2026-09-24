package com.qkt.connector.mt5

import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads deal history and quotes from the gateway.
 */
internal class MT5HistoryReads(
    private val gatewayUrl: String,
    private val json: Json,
    private val reads: MT5GatewayReads,
    private val snapshots: MT5SnapshotParser,
    private val venueTime: MT5VenueTime,
) {
    /**
     * The deals that closed venue position [positionTicket], via `GET /history_deals_get`:
     * the volume-weighted exit price plus the position's total venue costs (commission +
     * swap + fee across all its deals). This is the truth for a venue-side close (broker
     * SL/TP, manual close, stop-out) — the engine's last tick is a proxy that is stalest
     * exactly when venue-side closes happen.
     *
     * [fromUtcMs]/[toUtcMs] bound the search (the position's open time and now);
     * both are padded a day and shifted to venue time. Returns `null` when the
     * gateway can't be read or no closing deal exists in the window — callers fall
     * back to their best local proxy.
     */
    fun getClosingDeal(
        positionTicket: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): MT5ClosingDeal? {
        val deals = getPositionDeals(positionTicket, fromUtcMs, toUtcMs) ?: return null
        // DEAL_ENTRY_IN (0) opened the position; OUT (1) / INOUT (2) / OUT_BY (3)
        // reduced or closed it. The close may have happened in several partial deals —
        // volume-weight them into the single price the synthesized fill carries.
        var volume = BigDecimal.ZERO
        var notional = BigDecimal.ZERO
        var reported = BigDecimal.ZERO
        for (deal in deals) {
            reported = reported.add(deal.commission).add(deal.swap).add(deal.fee)
            if (deal.entry == 0 || deal.volume.signum() <= 0 || deal.price.signum() <= 0) continue
            volume = volume.add(deal.volume)
            notional = notional.add(deal.price.multiply(deal.volume))
        }
        if (volume.signum() == 0) return null
        return MT5ClosingDeal(
            price = notional.divide(volume, com.qkt.common.Money.CONTEXT),
            costs = reported.negate(),
            deals = deals,
        )
    }

    /**
     * All venue deals for [positionTicket] in the requested UTC range. The query is padded
     * by one day at each edge, matching [getClosingDeal], so entry-side costs are included.
     * The response is filtered locally because gateways may ignore the wire-level position
     * filter and return account-wide history.
     */
    fun getPositionDeals(
        positionTicket: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Deal>? {
        val from = venueTime.venueIso(fromUtcMs - DEAL_WINDOW_PAD_MS)
        val to = venueTime.venueIso(toUtcMs + DEAL_WINDOW_PAD_MS)
        val url = "$gatewayUrl/history_deals_get?from_date=$from&to_date=$to&position=$positionTicket"
        val raw = reads.get(url) ?: return null
        val arr = unwrapMT5Data(json.parseToJsonElement(raw)) as? JsonArray ?: return null
        return arr
            .map { snapshots.parseDeal(it.jsonObject) }
            .filter { it.positionTicket == positionTicket }
    }

    /**
     * Every deal the venue booked in `[fromUtcMs, toUtcMs]` — all positions, all symbols
     * — via `GET /history_deals_get` with a date range only (no position filter). Powers
     * the insights deal-history backfill. Bounds are shifted to venue time on the wire;
     * deal times come back as UTC millis. Returns `null` when the read FAILED (gateway
     * unreachable / non-2xx after retries) — callers must treat that as "unknown",
     * never as "no deals", or an outage silently skips a slice of history.
     */
    fun getDeals(
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Deal>? {
        val url = "$gatewayUrl/history_deals_get?from_date=${venueTime.venueIso(
            fromUtcMs,
        )}&to_date=${venueTime.venueIso(toUtcMs)}"
        val raw = reads.get(url) ?: return null
        val arr = unwrapMT5Data(json.parseToJsonElement(raw)) as? JsonArray ?: return null
        return arr.map { snapshots.parseDeal(it.jsonObject) }
    }

    fun getTick(brokerSymbol: String): MT5Tick? {
        val raw = reads.get("$gatewayUrl/symbol_info_tick/$brokerSymbol") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        val rawTime = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val rawTimeMs = obj["time_msc"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: rawTime * 1_000L
        val timeMs = venueTime.venueEpochToUtc(rawTimeMs)
        return MT5Tick(
            symbol = brokerSymbol,
            bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            time = timeMs / 1_000L,
            timeMs = timeMs,
        )
    }

    /** Return raw venue ticks for the inclusive UTC millisecond window. */
    fun getTicksRange(
        brokerSymbol: String,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Tick>? {
        require(toUtcMs >= fromUtcMs) { "MT5 tick-history range ends before it starts" }
        val from =
            URLEncoder.encode(
                Instant
                    .ofEpochMilli(fromUtcMs)
                    .toString(),
                Charsets.UTF_8,
            )
        val to =
            URLEncoder.encode(
                Instant
                    .ofEpochMilli(toUtcMs)
                    .toString(),
                Charsets.UTF_8,
            )
        val raw =
            reads.get(
                "$gatewayUrl/copy_ticks_range?symbol=$brokerSymbol&from_date=$from&to_date=$to",
            ) ?: return null
        val rows = unwrapMT5Data(json.parseToJsonElement(raw)) as? JsonArray ?: return null
        return rows.map { element ->
            val obj = element.jsonObject
            val time = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val timeMs =
                venueTime.venueEpochToUtc(
                    obj["time_msc"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: time * 1_000L,
                )
            MT5Tick(
                symbol = brokerSymbol,
                bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                time = timeMs / 1_000L,
                timeMs = timeMs,
            )
        }
    }

    private companion object {
        /** Padding either side of the deal search window — venue clock skew is hours, not days. */
        const val DEAL_WINDOW_PAD_MS: Long = 24L * 3600_000L
    }
}
