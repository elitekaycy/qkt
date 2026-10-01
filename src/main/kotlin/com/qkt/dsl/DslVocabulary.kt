package com.qkt.dsl

import com.qkt.dsl.ast.ExitField
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.TokenKind

/**
 * The one place the DSL's name tables live: the fields readable off a stream alias, the
 * members of every pseudo-symbol (`POSITION.<alias>.x`, `NOW.x`, `ACCOUNT.x`, ...), the series
 * selectors an indicator argument accepts, the indicators compiled outside
 * [com.qkt.dsl.stdlib.IndicatorRegistry], and the call-shaped shorthands the parser recognizes
 * by name. The parser and compiler read these tables, and so do the language server and
 * `qkt dsl vocabulary`, so the editor can only ever offer what the compiler accepts.
 *
 * Member spellings are lower case, the way they are written in a `.qkt` file; the parser
 * lower-cases what it reads before looking a member up.
 */
object DslVocabulary {
    /** Every keyword and operator-word spelling the lexer reserves, excluding the `->` token. */
    val keywords: List<String> = (Lexer.keywordSpellings() - TokenKind.ARROW.name).sorted()

    /** Fields describing the contract a futures stream follows right now; Undefined on other streams. */
    val contractFields: List<String> = listOf("contract", "dte", "days_to_roll")

    /** Per-bar fields readable off a stream alias, e.g. `btc.close`, plus the futures [contractFields]. */
    val candleFields: List<String> =
        listOf("close", "open", "high", "low", "volume", "price", "bid", "ask", "spread", "value", "timestamp") +
            contractFields

    /** The candle fields an indicator may consume as a numeric series. */
    val numericCandleFields: List<String> = listOf("close", "value", "open", "high", "low", "volume", "price")

    /** Instrument-metadata fields readable off a stream alias, e.g. `btc.tick_size`. */
    val metaFields: List<String> =
        listOf("tick_size", "contract_size", "volume_step", "volume_min", "swap_long_points", "swap_short_points") +
            listOf("tick_value", "multiplier")

    /** `<alias>.candle`: the whole closed candle, for candle-fed indicators such as `atr`. */
    const val CANDLE_SELECTOR = "candle"

    /** `<alias>.tick`: every raw tick, for tick-fed indicators such as `vwap`. */
    const val TICK_SELECTOR = "tick"

    /** The series selectors an indicator argument accepts after a stream alias. */
    val seriesSelectors: List<String> = listOf(CANDLE_SELECTOR, TICK_SELECTOR)

    /**
     * `POSITION.<alias>.<member>` spellings and what each compiles to: a null source is the
     * signed net quantity ([com.qkt.dsl.ast.PositionRef]); any other is a [StateSource] read. On a
     * structure alias (`OPEN <alias> = OPTIONS ON …`) quantity is the structure's size, `pnl` its
     * premium P&L, and `delta` through `pnl_pct` its structure fields.
     */
    val positionAccessors: Map<String, StateSource?> =
        linkedMapOf(
            "quantity" to null,
            "qty" to null,
            "entry_price" to StateSource.POSITION_AVG_PRICE,
            "avg_price" to StateSource.POSITION_AVG_PRICE,
            "avg_entry_price" to StateSource.POSITION_AVG_PRICE,
            "pnl" to StateSource.POSITION_PNL,
            "realized_pnl" to StateSource.POSITION_REALIZED_PNL,
            "unrealized_pnl" to StateSource.POSITION_UNREALIZED_PNL,
            "holding_duration" to StateSource.POSITION_HOLDING_DURATION,
            "mfe" to StateSource.POSITION_MFE,
            "mae" to StateSource.POSITION_MAE,
            "count" to StateSource.POSITION_OPEN_COUNT,
            "open_count" to StateSource.POSITION_OPEN_COUNT,
            "longs" to StateSource.POSITION_LONG_COUNT,
            "long_count" to StateSource.POSITION_LONG_COUNT,
            "shorts" to StateSource.POSITION_SHORT_COUNT,
            "short_count" to StateSource.POSITION_SHORT_COUNT,
            "gross" to StateSource.POSITION_GROSS,
            "trades_today" to StateSource.POSITION_TRADES_TODAY,
            "last_trade_at" to StateSource.POSITION_LAST_TRADE_AT,
            "delta" to StateSource.STRUCTURE_DELTA,
            "gamma" to StateSource.STRUCTURE_GAMMA,
            "vega" to StateSource.STRUCTURE_VEGA,
            "theta" to StateSource.STRUCTURE_THETA,
            "dte" to StateSource.STRUCTURE_DTE,
            "credit" to StateSource.STRUCTURE_CREDIT,
            "max_loss" to StateSource.STRUCTURE_MAX_LOSS,
            "pnl_pct" to StateSource.STRUCTURE_PNL_PCT,
        )

    /** `NOW.<member>` spellings and the clock field each reads. */
    val nowFields: Map<String, NowField> =
        linkedMapOf(
            "hour_utc" to NowField.HOUR_UTC,
            "minute_utc" to NowField.MINUTE_UTC,
            "weekday" to NowField.WEEKDAY,
            "month" to NowField.MONTH,
            "day" to NowField.DAY,
            "days_in_month" to NowField.DAYS_IN_MONTH,
            "date_utc" to NowField.DATE_UTC,
            "epoch_ms" to NowField.EPOCH_MS,
        )

    /** `EXIT.<member>` spellings (inside `ON_STOP`/`ON_TP`/`ON_CLOSE`) and the exit field each reads. */
    val exitFields: Map<String, ExitField> =
        linkedMapOf(
            "price" to ExitField.PRICE,
            "side" to ExitField.SIDE,
            "qty" to ExitField.QTY,
            "quantity" to ExitField.QTY,
            "pnl" to ExitField.PNL,
            "reason" to ExitField.REASON,
        )

    /** `ACCOUNT.<member>` P&L reads. */
    val accountPnlFields: List<String> = listOf("realized_pnl", "unrealized_pnl", "total_pnl", "equity", "balance")

    /** `ACCOUNT.<member>` trade-history reads. */
    val accountHistoryFields: List<String> =
        listOf(
            "last_trade_at",
            "last_trade_pnl",
            "win_streak",
            "loss_streak",
            "trades_today",
            "wins_today",
            "losses_today",
        )

    /** `ACCOUNT.<member>` risk reads. */
    val accountRiskFields: List<String> =
        listOf("dd_pct", "equity_peak", "open_positions_count", "realized_today", "realized_month")

    /** `STREAK.<member>`. */
    val streakMembers: List<String> = listOf("wins", "losses", "banked")

    /** `TRADES.<member>`. */
    val tradesMembers: List<String> = listOf("today")

    /** `COOLDOWN.<member>`. */
    val cooldownMembers: List<String> = listOf("remaining_s")

    /** `SEQUENCE.<name>.<member>`: the sequence's own state. */
    val sequenceMembers: List<String> = listOf("stage", "complete")

    /** `SEQUENCE.<name>.<stage>.<member>`: what a completed stage recorded. */
    val sequenceStageMembers: List<String> = listOf("price", "time")

    /** Owner name for the stage-level members in [members]. */
    const val SEQUENCE_STAGE_OWNER = "SEQUENCE_STAGE"

    /**
     * Every pseudo-symbol's member table, keyed by the owner keyword. `POSITION` members
     * follow an alias (`POSITION.btc.pnl`), `SEQUENCE` members follow a sequence name, and
     * [SEQUENCE_STAGE_OWNER] members follow a stage name inside a sequence.
     */
    val members: Map<String, List<String>> =
        linkedMapOf(
            TokenKind.POSITION.name to positionAccessors.keys.toList(),
            TokenKind.NOW.name to nowFields.keys.toList(),
            TokenKind.ACCOUNT.name to accountPnlFields + accountHistoryFields + accountRiskFields,
            TokenKind.EXIT.name to exitFields.keys.toList(),
            TokenKind.STREAK.name to streakMembers,
            TokenKind.TRADES.name to tradesMembers,
            TokenKind.COOLDOWN.name to cooldownMembers,
            TokenKind.SEQUENCE.name to sequenceMembers,
            SEQUENCE_STAGE_OWNER to sequenceStageMembers,
        )

    /** `resid(dependent, regressor1, …, period)`: a rolling OLS residual, bound outside the registry. */
    const val RESID = "RESID"

    /** `confirm_ratio(signal, peer1, …, lookback)`: cross-symbol confirmation, bound outside the registry. */
    const val CONFIRM_RATIO = "CONFIRM_RATIO"

    /** Indicators the compiler binds by name rather than through the registry (upper case). */
    val externalIndicators: List<String> = listOf(RESID, CONFIRM_RATIO)

    /** `avg(x, N)`: the rolling mean shorthand for `mean(x) SINCE T-N`. */
    const val AVG = "AVG"

    /** `count(cond, N)`: how many of the last N bars satisfied `cond`. */
    const val COUNT = "COUNT"

    /**
     * Call-shaped rolling shorthands over the last N bars: `avg(x, N)`, `count(cond, N)`, and the
     * `mean(x, N)` / `sum(x, N)` forms of the aggregate keywords (upper case).
     */
    val rollingShorthands: List<String> = listOf(AVG, COUNT, TokenKind.MEAN.name, TokenKind.SUM.name)

    /** `calendar_window(startMonth, startDay, endMonth, endDay)`: an annual date window. */
    const val CALENDAR_WINDOW = "CALENDAR_WINDOW"

    /** `session_window(startHour, startMinute, endHour, endMinute)`: a daily UTC time window. */
    const val SESSION_WINDOW = "SESSION_WINDOW"

    /** `last_trading_day_of_month()`: true on the last weekday of the UTC month. */
    const val LAST_TRADING_DAY_OF_MONTH = "LAST_TRADING_DAY_OF_MONTH"

    /** Clock-reading boolean predicates the parser recognizes by name (upper case). */
    val clockPredicates: List<String> = listOf(CALENDAR_WINDOW, SESSION_WINDOW, LAST_TRADING_DAY_OF_MONTH)
}
