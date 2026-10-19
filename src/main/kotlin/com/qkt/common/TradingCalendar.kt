package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.Instant

interface TradingCalendar {
    val name: String

    fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean

    fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange

    fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long

    fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange

    fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal = error("tradingPeriodsPerYear not implemented for $name")

    /**
     * True when [t] falls inside a scheduled intraday pause for [symbol] — a venue-published
     * break such as the metals/energy close at 17:00 New York. Distinct from [isInSession]:
     * feed polling and venue-state pollers key off the session (a break is too short to back
     * off for), while the market-data gate uses the break to classify a quote gap as expected
     * rather than as a feed fault. A venue whose pause closes the market (the CME Globex
     * 16:00–17:00 halt) may report it both as a break and as out of session. Defaults to never.
     */
    fun isScheduledBreak(
        symbol: String,
        t: Instant,
    ): Boolean = false

    /**
     * True when [session] (one [sessionRange]) is a scheduled full closure for [symbol], such as an exchange
     * holiday. Only data-coverage checks read it: a closed session is not required to hold data. Live session
     * gating keeps using [isInSession], so a venue that does quote on the day still streams. Defaults to never.
     */
    fun isClosedSession(
        symbol: String,
        session: TimeRange,
    ): Boolean = false

    /**
     * Whether a session can legitimately open late or close early (holiday hours): data coverage then accepts an
     * empty stretch at a session's edge as a short session instead of a hole. False for a 24/7 market, whose
     * session edges are only day boundaries.
     */
    val hasShortSessions: Boolean get() = true

    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar

        fun fxDefault(): TradingCalendar = FxCalendar

        fun nyse(): TradingCalendar = NyseCalendar

        /** The names [named] knows, for messages. */
        val names: List<String> = listOf("crypto", "fx", "nyse", "cme_globex")

        /** The calendar called [name] — `crypto`, `fx`, `nyse` or `cme_globex` — or null. */
        fun named(name: String): TradingCalendar? =
            when (name) {
                "crypto" -> CryptoCalendar
                "fx" -> FxCalendar
                "nyse" -> NyseCalendar
                "cme_globex" -> CmeGlobexCalendar
                else -> null
            }
    }
}
