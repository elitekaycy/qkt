package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the gateway's position, pending-order and deal snapshots into wire types. */
internal class MT5SnapshotParser(
    private val json: Json,
    private val venueTime: MT5VenueTime,
) {
    fun parsePositions(raw: String): List<MT5Position> {
        val arr = unwrapMT5Data(json.parseToJsonElement(raw)).jsonArray
        return arr.map { parsePosition(it.jsonObject) }
    }

    fun parsePendingOrders(raw: String): List<MT5PendingOrder> {
        // The gateway's /orders shape varies by version: some return a bare
        // array, others wrap it as {"orders": [...], "total": N}. Accept both.
        val arr =
            when (val root = unwrapMT5Data(json.parseToJsonElement(raw))) {
                is JsonArray -> root
                is JsonObject -> root["orders"]?.jsonArray ?: return emptyList()
                else -> return emptyList()
            }
        return arr.map { parsePendingOrder(it.jsonObject) }
    }

    fun parsePosition(obj: JsonObject): MT5Position {
        val rawTime = venueTime.venueEpochToUtc(obj["time_msc"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
        return MT5Position(
            ticket = obj["ticket"]!!.jsonPrimitive.content.toLong(),
            symbol = obj["symbol"]!!.jsonPrimitive.content,
            type = obj["type"]!!.jsonPrimitive.content.toInt(),
            volume = obj["volume"]!!.jsonPrimitive.content.toBigDecimal(),
            priceOpen = obj["price_open"]!!.jsonPrimitive.content.toBigDecimal(),
            sl = obj["sl"]!!.jsonPrimitive.content.toBigDecimal(),
            tp = obj["tp"]!!.jsonPrimitive.content.toBigDecimal(),
            profit = obj["profit"]!!.jsonPrimitive.content.toBigDecimal(),
            magic = obj["magic"]!!.jsonPrimitive.content.toInt(),
            openTime = rawTime,
            comment = obj["comment"]?.jsonPrimitive?.contentOrNull,
            clientOrderId = obj["client_order_id"]?.jsonPrimitive?.contentOrNull,
            swap = obj["swap"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            priceCurrent = obj["price_current"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
        )
    }

    /**
     * Parse one pending-order entry. Tolerant of partially-populated transient entries
     * — the gateway has been observed emitting rows mid-placement that lack a `ticket`
     * or `price_open` field. Defaulting those preserves the poller across a single bad
     * snapshot rather than killing the thread and missing all subsequent events.
     */
    fun parsePendingOrder(obj: JsonObject): MT5PendingOrder {
        val rawTime = venueTime.venueEpochToUtc(obj["time_setup"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
        val rawExp =
            venueTime.venueEpochToUtc(
                obj["time_expiration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        return MT5PendingOrder(
            ticket = obj["ticket"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            symbol = obj["symbol"]?.jsonPrimitive?.contentOrNull ?: "",
            // The gateway sends MT5's own record: `type` is the number (2), `type_str` the name
            // (BUY_LIMIT), and there is no `volume` - a resting order has `volume_current` (what is
            // still unfilled) and `volume_initial`. Reading `volume` gave every resting order a size of
            // zero, so one whose placement response was lost could never be matched to it (#1234).
            type = (obj["type_str"] ?: obj["type"])?.jsonPrimitive?.contentOrNull ?: "",
            volume =
                listOf("volume", "volume_current", "volume_initial")
                    .firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() }
                    ?: BigDecimal.ZERO,
            priceOpen = obj["price_open"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            sl = obj["sl"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tp = obj["tp"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            magic = obj["magic"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            timeSetup = rawTime,
            timeExpiration = rawExp,
            comment = obj["comment"]?.jsonPrimitive?.contentOrNull,
            clientOrderId = obj["client_order_id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Tolerant of missing fields like [parsePendingOrder] — one bad row must not kill a backfill. */
    fun parseDeal(obj: JsonObject): MT5Deal {
        val rawTimeMs = obj["time_msc"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val timeMs = venueTime.venueEpochToUtc(rawTimeMs)
        return MT5Deal(
            ticket = obj["ticket"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            orderTicket = obj["order"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            positionTicket = obj["position_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            symbol = obj["symbol"]?.jsonPrimitive?.contentOrNull ?: "",
            type = obj["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            entry = obj["entry"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            volume = obj["volume"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            price = obj["price"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            profit = obj["profit"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            commission = obj["commission"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            swap = obj["swap"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            fee = obj["fee"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            magic = obj["magic"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            comment = obj["comment"]?.jsonPrimitive?.contentOrNull,
            timeMs = timeMs,
            clientOrderId = obj["client_order_id"]?.jsonPrimitive?.contentOrNull,
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }
}
