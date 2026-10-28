package com.qkt.execution

import com.qkt.common.TradingCalendar
import java.time.Duration
import java.time.Instant

/**
 * When a DAY order placed at [nowMs] on [symbol] expires under [calendar]: the end of the symbol's current session, or,
 * once that session has ended or does not trade (a weekend, a holiday), the end of the next open one. An order placed after the 15:45 close
 * of a stock CFD therefore rests through the next session instead of expiring on arrival, as an exchange treats a day
 * order entered after the close. FX sessions end at the 17:00 New York roll, crypto's at midnight UTC.
 */
fun dayExpiryMs(
    calendar: TradingCalendar,
    symbol: String,
    nowMs: Long,
): Long {
    val cal = calendar.forSymbol(symbol)
    val first = cal.sessionRange(symbol, Instant.ofEpochMilli(nowMs))
    var session = first
    repeat(MAX_SESSIONS_AHEAD) {
        if (session.to.toEpochMilli() > nowMs && trades(cal, symbol, session)) return session.to.toEpochMilli()
        session = cal.sessionRange(symbol, session.to.plus(STEP))
    }
    return first.to.toEpochMilli()
}

/**
 * True when [session] is one the symbol actually trades: its middle is in session. A calendar answers a range for any
 * date, a Saturday or a holiday included, and only a trading day's range is open in the middle.
 */
private fun trades(
    calendar: TradingCalendar,
    symbol: String,
    session: com.qkt.common.TimeRange,
): Boolean {
    val mid = session.from.toEpochMilli() + (session.to.toEpochMilli() - session.from.toEpochMilli()) / 2
    return calendar.isInSession(symbol, Instant.ofEpochMilli(mid))
}

/** [request] with a DAY expiry stamped when it is a DAY order that carries none yet; any other order unchanged. */
fun OrderRequest.withDayExpiry(
    calendar: TradingCalendar,
    nowMs: Long,
): OrderRequest =
    if (timeInForce == TimeInForce.DAY &&
        expiresAt == null
    ) {
        withExpiresAt(dayExpiryMs(calendar, symbol, nowMs))
    } else {
        this
    }

/** A long holiday stretch is a few days; two weeks ahead is a calendar that never opens. */
private const val MAX_SESSIONS_AHEAD = 14

/** Past a session's end and into the next calendar day of every calendar qkt has. */
private val STEP: Duration = Duration.ofHours(12)
