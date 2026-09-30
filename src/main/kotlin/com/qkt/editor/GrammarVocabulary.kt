package com.qkt.editor

import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.parse.KeywordCategory
import com.qkt.dsl.stdlib.Constants
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry

/**
 * The names the generated grammars highlight, each list read from the implementation that
 * accepts it: keywords from [KeywordCategory], indicators and functions from the registries,
 * constants from [Constants], stream fields from [ExprCompiler].
 *
 * The pseudo-symbol member tables, the two indicators compiled outside the registry and the
 * clock and rolling call names are spelled here because they live in parser `when` branches;
 * the `DslVocabulary` object centralizes them, and this file will read from it once merged.
 */
object GrammarVocabulary {
    /** Pseudo-symbols read with a dot: `POSITION.gold.qty`, `NOW.hour_utc`. All are lexer keywords. */
    val pseudoSymbols: List<String> =
        listOf(
            "POSITION",
            "POSITION_AVG_PRICE",
            "OPEN_ORDERS",
            "NOW",
            "ACCOUNT",
            "EXIT",
            "STREAK",
            "TRADES",
            "COOLDOWN",
            "SEQUENCE",
        )

    /** Indicators the compiler special-cases before consulting [IndicatorRegistry]. */
    val registryExternalIndicators: List<String> = listOf("RESID", "CONFIRM_RATIO")

    /** Calls the parser resolves itself: calendar predicates and the rolling shorthands. */
    val parserBuiltins: List<String> =
        listOf("CALENDAR_WINDOW", "SESSION_WINDOW", "LAST_TRADING_DAY_OF_MONTH", "AVG", "COUNT")

    private val positionMembers =
        listOf(
            "quantity",
            "qty",
            "entry_price",
            "avg_price",
            "avg_entry_price",
            "pnl",
            "realized_pnl",
            "unrealized_pnl",
            "holding_duration",
            "mfe",
            "mae",
            "count",
            "open_count",
            "longs",
            "long_count",
            "shorts",
            "short_count",
            "gross",
            "trades_today",
            "last_trade_at",
        )
    private val exitMembers = listOf("price", "side", "qty", "quantity", "pnl", "reason")
    private val accountMembers =
        listOf(
            "realized_pnl",
            "unrealized_pnl",
            "total_pnl",
            "equity",
            "balance",
            "last_trade_at",
            "last_trade_pnl",
            "win_streak",
            "loss_streak",
            "trades_today",
            "wins_today",
            "losses_today",
            "dd_pct",
            "equity_peak",
            "open_positions_count",
            "realized_today",
            "realized_month",
        )
    private val streakMembers = listOf("wins", "losses", "banked")
    private val tradesMembers = listOf("today")
    private val cooldownMembers = listOf("remaining_s")
    private val sequenceMembers = listOf("stage", "complete", "price", "time")
    private val nowMembers =
        listOf("hour_utc", "minute_utc", "weekday", "month", "day", "days_in_month", "date_utc", "epoch_ms")

    /** Every spelling that can follow a dot on a stream alias or pseudo-symbol, lowercase, sorted. */
    val memberFields: List<String> =
        (
            ExprCompiler.CANDLE_FIELDS + ExprCompiler.META_FIELDS + listOf("candle", "tick") +
                positionMembers + exitMembers + accountMembers + streakMembers + tradesMembers +
                cooldownMembers + sequenceMembers + nowMembers
        ).map { it.lowercase() }.distinct().sorted()

    /** Registered indicators plus the two compiled outside the registry, uppercase, sorted. */
    val indicators: List<String> = (IndicatorRegistry.names() + registryExternalIndicators).sorted()

    /** Registered scalar functions plus the parser's own call names, uppercase, sorted. */
    val functions: List<String> = (FuncRegistry.names() + parserBuiltins).sorted()

    /** Named numeric constants, sorted. */
    val constants: List<String> = Constants.names().sorted()

    /** The action verbs that take a stream alias as their next word. */
    val aliasTakingActions: List<String> = listOf("BUY", "SELL", "CLOSE", "RESIZE")

    /** Keywords of [category], sorted for a stable grammar. */
    fun keywords(category: KeywordCategory): List<String> = KeywordCategory.spellings(category).sorted()
}
