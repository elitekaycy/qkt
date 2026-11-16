package com.qkt.cli.bot

import com.qkt.connectivity.DirectDeal
import com.qkt.connectivity.DirectPendingOrder
import com.qkt.connectivity.DirectPosition
import com.qkt.trade.venue.idJson

/*
 * The venue rows `qkt bot positions`, `orders` and `history` print. A venue reports its own codes; each row names
 * the qkt symbol as `symbol` ([com.qkt.connectivity.DirectVenue.qktSymbol]), the one `quote`, `buy` and `close` take,
 * and keeps the venue's code as `venueSymbol`.
 */

internal fun DirectPosition.doc(qktSymbol: String): String =
    jsonObj(
        "ticket" to idJson(ticket),
        "symbol" to qktSymbol,
        "venueSymbol" to symbol,
        "side" to side,
        "lots" to lots,
        "entry" to entry,
        "current" to current,
        "sl" to sl,
        "tp" to tp,
        "profit" to profit,
        "swap" to swap,
        "openedAtMs" to openedAtMs,
        "comment" to comment,
    )

internal fun DirectPendingOrder.doc(qktSymbol: String): String =
    jsonObj(
        "ticket" to idJson(ticket),
        "symbol" to qktSymbol,
        "venueSymbol" to symbol,
        "type" to type,
        "lots" to lots,
        "price" to price,
        "sl" to sl,
        "tp" to tp,
        "expiresAt" to expiresAt,
    )

internal fun DirectDeal.doc(qktSymbol: String): String =
    jsonObj(
        "dealTicket" to idJson(dealTicket),
        "positionTicket" to positionTicket?.let(::idJson),
        "symbol" to qktSymbol,
        "venueSymbol" to symbol,
        "side" to side,
        "entry" to entry,
        "lots" to lots,
        "price" to price,
        "profit" to profit,
        "commission" to commission,
        "swap" to swap,
        "fee" to fee,
        "timeMs" to timeMs,
        "comment" to comment,
    )
