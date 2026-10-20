package com.qkt.trade.session

import java.math.BigDecimal

// The JSON rows of a session's fates, events and legs, written by hand as the rest of the session API is.

private fun str(v: String?): String = v?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null"

private fun num(v: BigDecimal?): String = v?.toPlainString() ?: "null"

internal fun fateJson(f: IntentFate): String =
    """{"intentId":"${f.intentId}","identity":${str(f.identity)},"state":"${f.state}",""" +
        """"orderIds":[${f.orderIds.joinToString(",") { str(it) }}],"reason":${str(f.reason)},""" +
        """"atMs":${f.atMs ?: "null"},"compiled":${f.compiled},"tickMs":${f.tickMs ?: "null"}}"""

internal fun eventJson(e: SessionEvent): String =
    """{"seq":${e.seq},"kind":"${e.kind}","identity":${str(e.identity)},"intentId":${str(e.intentId)},""" +
        """"orderId":${str(e.orderId)},"symbol":${str(e.symbol)},"side":${str(e.side)},"price":${num(e.price)},""" +
        """"quantity":${num(
            e.quantity,
        )},"exitReason":${str(e.exitReason)},"reason":${str(e.reason)},"atMs":${e.atMs}}"""

internal fun legJson(l: SessionLeg): String =
    """{"identity":${str(l.identity)},"side":"${l.side}","qty":${num(l.quantity)},"entry":${num(l.entry)},""" +
        """"ticket":${str(l.ticket)},"legId":${str(l.legId)}}"""

internal fun workingJson(w: WorkingIntentOrder): String =
    """{"intentId":"${w.intentId}","identity":${str(w.identity)},"orderId":${str(w.orderId)}}"""
