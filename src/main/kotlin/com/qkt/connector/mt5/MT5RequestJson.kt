package com.qkt.connector.mt5

import java.math.BigDecimal

/** The `POST /order` body for [req]. */
internal fun orderJson(req: MT5OrderRequest): String {
    val sb = StringBuilder("{")

    fun field(
        name: String,
        value: String,
        last: Boolean = false,
    ) {
        sb.append("\"$name\":$value")
        if (!last) sb.append(",")
    }
    field("symbol", "\"${req.symbol}\"")
    field("volume", req.volume.toPlainString())
    field("type", "\"${req.type}\"")
    if (req.price != null) field("price", req.price.toPlainString())
    if (req.sl != null) field("sl", req.sl.toPlainString())
    if (req.tp != null) field("tp", req.tp.toPlainString())
    if (req.stopLimit != null) field("stoplimit", req.stopLimit.toPlainString())
    if (req.slDistance != null) field("sl_distance", req.slDistance.toString())
    field("deviation", req.deviation.toString())
    field("magic", req.magic.toString())
    field("client_order_id", "\"${req.clientOrderId}\"")
    // GTD expiry (epoch seconds). Without this a GTD pending rests GTC-forever on MT5
    // and fills late. Mirrors encodeModification, which the gateway accepts without an
    // explicit type_time — it infers TIME_SPECIFIED from the expiration's presence.
    if (req.expiration != null) field("expiration", req.expiration.toString())
    field("comment", "\"${req.comment.take(MT5_COMMENT_MAX_LENGTH)}\"", last = true)
    sb.append("}")
    return sb.toString()
}

/** The `PATCH /orders/<ticket>` body: only the fields being changed. */
internal fun orderModificationJson(m: MT5OrderModification): String {
    val fields = mutableListOf<String>()
    if (m.price != null) fields += "\"price\":${m.price.toPlainString()}"
    if (m.sl != null) fields += "\"sl\":${m.sl.toPlainString()}"
    if (m.tp != null) fields += "\"tp\":${m.tp.toPlainString()}"
    if (m.slDistance != null) fields += "\"sl_distance\":${m.slDistance}"
    if (m.expiration != null) fields += "\"expiration\":${m.expiration}"
    return "{" + fields.joinToString(",") + "}"
}

/** The `POST /close_position` body; a null [volume] closes the whole ticket. */
internal fun closePositionJson(
    ticket: Long,
    volume: BigDecimal?,
): String =
    if (volume != null) {
        "{\"position\":{\"ticket\":$ticket,\"volume\":${volume.toPlainString()}}}"
    } else {
        "{\"position\":{\"ticket\":$ticket}}"
    }

/** The `POST /position_close_partial` body. */
internal fun partialCloseJson(
    ticket: Long,
    volume: BigDecimal,
): String = "{\"ticket\":$ticket,\"volume\":${volume.toPlainString()}}"

/** The `POST /modify_sl_tp` body; an omitted level is cleared by the gateway, so pass both to keep both. */
internal fun modifyPositionJson(
    ticket: Long,
    sl: BigDecimal?,
    tp: BigDecimal?,
): String {
    val fields = mutableListOf("\"position\":$ticket")
    if (sl != null) fields += "\"sl\":${sl.toPlainString()}"
    if (tp != null) fields += "\"tp\":${tp.toPlainString()}"
    return "{" + fields.joinToString(",") + "}"
}
