package com.qkt.broker.mt5

import com.qkt.common.TradingCalendar
import java.time.Instant

/**
 * Resolves a qkt symbol to the [TradingCalendar] that governs its trading sessions.
 *
 * A single MT5 broker can offer instruments from different asset classes — FX (closed at the
 * weekend), crypto (24/7), index CFDs (NYSE hours). Each needs its own session calendar, so the
 * calendar is chosen per symbol from an ordered list of glob rules: the first rule whose pattern
 * matches wins, and a symbol that matches nothing falls back to [default].
 *
 * Patterns are globs on the bare qkt symbol (no `NAME:` prefix), where `*` matches any run of
 * characters. e.g. rules `[BTC* -> crypto, * -> fx]` resolve "BTCUSD" to crypto and "EURUSD" to fx.
 */
class SymbolCalendars(
    rules: List<Rule>,
    private val default: TradingCalendar,
) {
    /** One `pattern -> calendar` mapping. [pattern] is a glob on the bare qkt symbol. */
    data class Rule(
        val pattern: String,
        val calendar: TradingCalendar,
    )

    private val compiled: List<Pair<Regex, TradingCalendar>> =
        rules.map { globToRegex(it.pattern) to it.calendar }

    /** The calendar governing [bareSymbol] (a qkt symbol with no `NAME:` prefix). */
    fun calendarFor(bareSymbol: String): TradingCalendar =
        compiled.firstOrNull { it.first.matches(bareSymbol) }?.second ?: default

    /**
     * True when at least one of [symbols] is currently in session under its own calendar.
     *
     * The account-wide MT5 pollers use this: a broker counts as "open" if any instrument it
     * trades is open, so weekend crypto still reconciles while FX sleeps. [symbols] are bare
     * qkt symbols.
     */
    fun anyInSession(
        symbols: Collection<String>,
        t: Instant,
    ): Boolean = symbols.any { calendarFor(it).isInSession(it, t) }

    /** The distinct calendars referenced by the rules plus the default (for diagnostics/logging). */
    val calendars: Set<TradingCalendar>
        get() = (compiled.map { it.second } + default).toSet()

    /**
     * True when any configured calendar reports [t] in-session. Used by the account-wide MT5
     * pollers, which have no per-symbol subscription list: a broker is "open" if any asset class
     * it's configured for is open (so a 24/7 crypto calendar keeps the poller running while FX
     * sleeps). For an all-FX resolver this is exactly the historical single-calendar check.
     */
    fun anyCalendarInSession(t: Instant): Boolean = calendars.any { it.isInSession("", t) }

    companion object {
        /** All-FX resolver — the behaviour for a profile that declares no calendar rules. */
        fun fxDefault(): SymbolCalendars = SymbolCalendars(emptyList(), TradingCalendar.fxDefault())

        /**
         * Compile a glob ([pattern] with `*` wildcards) to an anchored [Regex]. Literal segments
         * are escaped so symbol characters like `.` are matched literally. e.g. `XAU*` -> matches
         * "XAUUSD"; `*` -> matches anything; `EURUSD` -> matches only "EURUSD".
         */
        internal fun globToRegex(pattern: String): Regex =
            Regex("^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$")
    }
}
