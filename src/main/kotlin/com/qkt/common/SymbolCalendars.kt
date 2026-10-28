package com.qkt.common

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
 *
 * A pattern `category:<glob>` matches the folder the venue files the symbol under instead ([categoryOf], e.g.
 * `Stocks` for MT5's `Standard\Stocks\AAPLm`), so one rule covers a broker's whole stock list. Symbol rules are
 * tried first, so a config can still give one stock its own calendar.
 */
class SymbolCalendars(
    private val rules: List<Rule>,
    private val default: TradingCalendar,
    /** The venue's category for a bare symbol, or null when it reports none; never part of equality. */
    private val categoryOf: (String) -> String? = { null },
) {
    /**
     * These calendars with [overrides] layered on top: an override for a pattern replaces that
     * pattern's rule, new patterns are added ahead of the existing ones, and a `*` override sets the
     * fallback instead of shadowing every specific rule. e.g. a profile that pauses `XAU*` at the
     * New York close, overridden with `BTC* -> crypto, * -> fx`, still pauses gold and now trades
     * BTC at weekends - before, the override replaced the lot and gold lost its daily break.
     */
    fun overriddenBy(overrides: List<Rule>): SymbolCalendars {
        val catchAll = overrides.lastOrNull { it.pattern == "*" }
        val specific = overrides.filter { it.pattern != "*" }
        val kept = rules.filter { base -> specific.none { it.pattern == base.pattern } }
        return SymbolCalendars(specific + kept, catchAll?.calendar ?: default, categoryOf)
    }

    /** These calendars reading each symbol's venue category from [lookup], for `category:` rules. */
    fun withCategories(lookup: (String) -> String?): SymbolCalendars = SymbolCalendars(rules, default, lookup)

    /** One `pattern -> calendar` mapping. [pattern] is a glob on the bare qkt symbol. */
    data class Rule(
        val pattern: String,
        val calendar: TradingCalendar,
    )

    private val bySymbol: List<Pair<Regex, Rule>> =
        rules.filterNot { it.pattern.startsWith(CATEGORY) }.map { globToRegex(it.pattern) to it }

    private val byCategory: List<Pair<Regex, Rule>> =
        rules.filter { it.pattern.startsWith(CATEGORY) }.map { globToRegex(it.pattern.removePrefix(CATEGORY)) to it }

    /** The calendar governing [bareSymbol] (a qkt symbol with no `NAME:` prefix). */
    fun calendarFor(bareSymbol: String): TradingCalendar = ruleFor(bareSymbol)?.calendar ?: default

    /** The first rule whose pattern matches [bareSymbol], or null when it takes the fallback calendar. */
    fun ruleFor(bareSymbol: String): Rule? =
        bySymbol.firstOrNull { it.first.matches(bareSymbol) }?.second
            ?: byCategory
                .takeIf { it.isNotEmpty() }
                ?.let { rules ->
                    categoryOf(bareSymbol)?.let { c -> rules.firstOrNull { it.first.matches(c) }?.second }
                }

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

    /** Whether qkt [symbol] (`NAME:` prefix optional) is in session at [nowMs] under its own calendar. */
    fun inSession(
        symbol: String,
        nowMs: Long,
    ): Boolean {
        val bare = symbol.substringAfter(':')
        return calendarFor(bare).isInSession(bare, Instant.ofEpochMilli(nowMs))
    }

    /** Whether qkt [symbol] (`NAME:` prefix optional) is inside its calendar's scheduled break at [nowMs]. */
    fun scheduledBreak(
        symbol: String,
        nowMs: Long,
    ): Boolean {
        val bare = symbol.substringAfter(':')
        return calendarFor(bare).isScheduledBreak(bare, Instant.ofEpochMilli(nowMs))
    }

    /** The distinct calendars referenced by the rules plus the default (for diagnostics/logging). */
    val calendars: Set<TradingCalendar>
        get() = (rules.map { it.calendar } + default).toSet()

    /**
     * True when any configured calendar reports [t] in-session. Used by the account-wide MT5
     * pollers, which have no per-symbol subscription list: a broker is "open" if any asset class
     * it's configured for is open (so a 24/7 crypto calendar keeps the poller running while FX
     * sleeps). For an all-FX resolver this is exactly the historical single-calendar check.
     */
    fun anyCalendarInSession(t: Instant): Boolean = calendars.any { it.isInSession("", t) }

    // Structural equality over the rule list and default calendar, so broker profiles can be
    // grouped by market-data identity (com.qkt.cli.MarketSourceFactory). The built-in calendars
    // are singletons, so Rule's data-class equality compares them correctly.
    override fun equals(other: Any?): Boolean =
        other is SymbolCalendars && rules == other.rules && default == other.default

    override fun hashCode(): Int = 31 * rules.hashCode() + default.hashCode()

    companion object {
        /** The prefix of a rule pattern matched against the venue's category of a symbol. */
        const val CATEGORY: String = "category:"

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
