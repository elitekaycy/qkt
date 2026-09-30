package com.qkt.lsp

/**
 * Hover one-liners for the clause and expression keywords: order types and time in force,
 * sizing, brackets, latches, operators, aggregates and the pseudo-symbols. Each line states the
 * form the parser accepts; [QktKeywordDocs] covers the structural keywords. Keyed upper case.
 */
internal object QktClauseDocs {
    val entries: Map<String, String> =
        mapOf(
            "MARKET" to "Order type `MARKET`; also a STACK layer type and `ENTER MARKET` in a LATCH.",
            "LIMIT" to
                "`LIMIT AT <price>`; `STOP AT <p> LIMIT AT <p>` is a stop-limit; `OCO { ... LIMIT AT <p> }`; " +
                "inside an exit hook `LIMIT WITH|AGAINST <dist>`.",
            "STOP" to "`STOP AT <price>` order; `STOP LOSS <price form>` in BRACKET; `OCO { STOP AT <p>, ... }`.",
            "TRAILING" to
                "Order type `TRAILING BY <dist>` / `TRAILING PCT <n>` (also `DEFAULTS { TRAILING = ... }`); " +
                "stop leg `TRAILING <dist> AFTER MFE >= <t>` arms a trail.",
            "AT" to
                "A price or time: `LIMIT AT`, `STOP AT`, bracket `AT <price>`, STACK layer `AT`, `SCHEDULE AT HH:MM`.",
            "BY" to "`BY <distance>` bracket leg offset from entry; `TRAILING BY`; `TIGHTEN BY`.",
            "TO" to "`RESIZE <alias> TO <sizing>`; `STEP TO BREAKEVEN|ENTRY [+ <d>]`.",
            "PCT" to
                "`PCT <n>` leg at a percent of entry; `BY <n> PCT`; `TRAILING PCT`; `SIZING <n> PCT OF " +
                "EQUITY|BALANCE`; `SIZING <n> PCT RISK [OF BOOK]`.",
            "STEP" to "Stepped stop: `BY <d> STEP TO BREAKEVEN|ENTRY [+ <d>] AFTER MFE >= <t> [STEP TO ...]`.",
            "TIGHTEN" to "Time-tightened stop: `BY <d> TIGHTEN BY <d> EVERY <duration> FLOOR <d>`.",
            "FLOOR" to "`FLOOR <dist>`: the smallest distance a TIGHTEN stop reaches. In expressions `floor(x)`.",
            "SIZING" to
                "`SIZING <qty>` | `<n> USD` | `<n> PCT OF EQUITY|BALANCE` | `RISK $ <amt>` | `RISK <frac> " +
                "[OF BOOK]` | `<n> PCT RISK [OF BOOK]` | `POSITION.<alias>`.",
            "MIN_STEP" to "`RESIZE ... MIN_STEP <expr>`: the smallest size change worth sending.",
            "RISK" to
                "`SIZING RISK $ <amount>` risks that amount to the stop; `RISK <frac> [OF BOOK]` risks a fraction.",
            "USD" to "`SIZING <n> USD`: size by notional value.",
            "OF" to "`PCT OF EQUITY|BALANCE`; `RISK ... OF BOOK` re-bases on the portfolio book.",
            "EQUITY" to "`SIZING <n> PCT OF EQUITY`; `SERIES ACCOUNT.EQUITY` in SYMBOLS.",
            "BALANCE" to "`SIZING <n> PCT OF BALANCE`.",
            "POSITION" to
                "`POSITION.<alias>` net quantity (a basket gives +1/-1/0 direction); `POSITION.<alias>.<accessor>`; " +
                "`SIZING POSITION.<alias>` is the whole position.",
            "POSITION_AVG_PRICE" to
                "`POSITION_AVG_PRICE.<alias>`: average entry price, like `POSITION.<alias>.entry_price`.",
            "OPEN_ORDERS" to "`OPEN_ORDERS.<alias>`: count of open entry orders on a stream.",
            "BRACKET" to
                "`BRACKET { STOP LOSS <price form>, TAKE PROFIT <price form> }`; forms are AT, BY, PCT, RR, TRAILING.",
            "OCO" to "`OCO { STOP AT <p>, LIMIT AT <p> }`: an exit pair where one fill cancels the other.",
            "ON" to "`SEQUENCE <name> ON <alias>`; `ENTER ON <alias>` inside a LATCH.",
            "TAKE" to "`TAKE PROFIT <price form>` inside BRACKET.",
            "PROFIT" to "Second word of `TAKE PROFIT`.",
            "LOSS" to "Second word of `STOP LOSS`.",
            "RR" to "`RR <n>`: take profit at n times the stop distance.",
            "LATCH" to
                "`LATCH <alias> OFFSET <d> [FROM <ref>] ARM <duration> [AS <name>] [CONFIRM ...] { ENTER ... " +
                "[; ENTER ...] }`: a breakout latch.",
            "ENTER" to
                "`ENTER [ON <alias>] MARKET|LIMIT <dir>|STOP <dir> [BRACKET {...}] [SIZING ...] [EXPIRE <d>]` " +
                "inside a LATCH block.",
            "OFFSET" to "`LATCH ... OFFSET <dist>`: distance of the latch level from its reference.",
            "ARM" to "`ARM <duration>`: how long the latch stays armed.",
            "WITH" to "`WITH <dist>`: a price in the break or exit direction (LATCH legs, exit-hook orders).",
            "AGAINST" to "`AGAINST <dist>`: a price against the break or exit direction.",
            "RETRACE" to "`RETRACE <dist>`: same as AGAINST.",
            "FROM" to "`OFFSET <d> FROM <ref>`: the reference level of a latch.",
            "EXPIRE" to "`EXPIRE <duration>`: when a latch entry expires.",
            "CONFIRM" to "`CONFIRM CLOSE_BEYOND | TIME_IN_BREACH <d> | RETEST_HOLD <dist> WITHIN <d>` on a LATCH.",
            "CLOSE_BEYOND" to "Latch confirmation: a bar closes beyond the level.",
            "TIME_IN_BREACH" to "Latch confirmation: price stays beyond the level for a duration.",
            "RETEST_HOLD" to "Latch confirmation: price retests within a distance and holds within a duration.",
            "TIF" to "`TIF GTC|IOC|FOK|DAY|GTD [UNTIL] <expr>` on an action; `DEFAULTS { TIF = ... }`.",
            "GTC" to "Time in force: good till cancelled.",
            "IOC" to "Time in force: immediate or cancel.",
            "FOK" to "Time in force: fill or kill.",
            "DAY" to "Time in force: good for the day. Also `SCHEDULE EVERY DAY AT ...` and `NOW.day`.",
            "GTD" to "`TIF GTD [UNTIL] <expr>`: good till the given time.",
            "UNTIL" to "Optional word in `TIF GTD UNTIL <expr>`.",
            "EVERY" to
                "Stream timeframe `EVERY 1m`; `SCHEDULE EVERY HOUR|DAY|WEEKDAY`; `REBALANCE EVERY <d>`; " +
                "`TIGHTEN BY <d> EVERY <d>`.",
            "CROSSES" to "`<a> CROSSES ABOVE|BELOW <b>`: true on the bar the left side crosses the right side.",
            "ABOVE" to "`CROSSES ABOVE`; `STACK <n> SPACING <d> ABOVE` stacks upward.",
            "BELOW" to "`CROSSES BELOW`; `STACK <n> SPACING <d> BELOW` stacks downward.",
            "BETWEEN" to "`<expr> BETWEEN <lo> AND <hi>`: inclusive range test.",
            "IS" to "`<expr> IS NULL` / `<expr> IS NOT NULL`.",
            "NULL" to "The missing value; indicators return it during warmup. Test with `IS NULL`.",
            "AND" to "Boolean conjunction; also the separator in `BETWEEN <lo> AND <hi>`.",
            "OR" to "Boolean disjunction.",
            "NOT" to "`NOT <cond>` negation; `IS NOT NULL`.",
            "CASE" to "`CASE WHEN <cond> THEN <v> [WHEN ... THEN ...] ELSE <v> END`.",
            "ELSE" to "Required fallback branch of CASE.",
            "END" to "Closes a CASE expression.",
            "SINCE" to "`max|min|mean|sum(<expr>) SINCE OPEN` or `SINCE T-<n>`: a windowed aggregate.",
            "OPEN" to "`SINCE OPEN` (since the position opened); `x@open` snapshot; candle field `open`.",
            "MAX" to "`max(a, b, ...)` largest value, or `max(<expr>) SINCE ...` aggregate.",
            "MIN" to "`min(a, b, ...)` smallest value, or `min(<expr>) SINCE ...` aggregate.",
            "MEAN" to "`mean(<expr>) SINCE ...` or `mean(<expr>, N)`: rolling mean.",
            "SUM" to "`sum(<expr>) SINCE ...` or `sum(<expr>, N)`: rolling sum.",
            "ACCOUNT" to
                "`ACCOUNT.<field>`: P&L, trade history and risk reads such as `equity`, `dd_pct`, `trades_today`.",
            "STREAK" to "`STREAK.wins`, `STREAK.losses`, `STREAK.banked`.",
            "TRADES" to "`TRADES.today`: trades taken today.",
            "COOLDOWN" to "`COOLDOWN.remaining_s`: seconds left in the trade cooldown.",
            "SYMBOL" to
                "Inside DEFAULTS: placeholder for the action's own stream, e.g. `STOP_LOSS = BY atr(SYMBOL, 14)`.",
            "NOW" to "`NOW` is epoch milliseconds; `NOW.<field>` reads hour_utc, minute_utc, weekday, month, day, ...",
            "OCO_ENTRY" to "`OCO_ENTRY { BUY ..., SELL ... }`: two entry legs where one fill cancels the other.",
            "STACK_AT" to
                "`STACK_AT MFE >= <t> WITHIN <d> SIZING <s> BRACKET {...}` or `STACK_AT MAE >= <t> RECOVER <d> " +
                "WITHIN <d> ...`: add to a winner or a recovered loser.",
            "MFE" to "Maximum favorable excursion: `STACK_AT MFE >= <t>`, `AFTER MFE >= <t>`, `POSITION.<alias>.mfe`.",
            "MAE" to "Maximum adverse excursion: `STACK_AT MAE >= <t> RECOVER <d>`, `POSITION.<alias>.mae`.",
            "RECOVER" to "`STACK_AT MAE >= <t> RECOVER <dist>`: how far price must recover before stacking.",
            "ENTRY_QTY" to "Only inside a STACK_AT SIZING: the parent entry's quantity.",
            "EXIT" to
                "`EXIT AFTER <duration>` timed exit; `EXIT.price|side|qty|pnl|reason` inside ON_STOP/ON_TP/ON_CLOSE.",
            "TRUE" to "Boolean literal.",
            "FALSE" to "Boolean literal.",
        )
}
