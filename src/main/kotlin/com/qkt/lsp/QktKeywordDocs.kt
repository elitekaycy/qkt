package com.qkt.lsp

/**
 * Hover one-liners for the structural keywords: file headers and blocks, rules, actions,
 * stacking, portfolio composition, schedules and the exit hooks. Each line states the form the
 * parser accepts; [QktClauseDocs] covers the clause and expression keywords. Keyed upper case.
 */
internal object QktKeywordDocs {
    val entries: Map<String, String> =
        mapOf(
            "STRATEGY" to
                "File header `STRATEGY <name> VERSION <n>`; blocks follow in order DEFAULTS, SYMBOLS, PARAM, " +
                "LET, SCHEDULE, SEQUENCE, RULES.",
            "VERSION" to "Integer version in the `STRATEGY` / `PORTFOLIO` header, e.g. `VERSION 1`.",
            "DEFAULTS" to
                "`DEFAULTS { SIZING = ... ORDER_TYPE = ... TIF = ... STOP_LOSS = ... TAKE_PROFIT = ... " +
                "TRAILING = ... }`: inherited by every BUY/SELL that does not set its own.",
            "SYMBOLS" to
                "Block of `<alias> = <BROKER>:<SYMBOL> EVERY <tf> [WARMUP <n> BARS]`, `<alias> = BASKET ...`, " +
                "`<alias> = SERIES ...`, then SYNCHRONIZE groups.",
            "SYNCHRONIZE" to
                "`SYNCHRONIZE <a> <b> [...] [WITHIN <duration>]` at the end of SYMBOLS: those aliases are " +
                "evaluated on same-window bars.",
            "BASKET" to
                "`<alias> = BASKET EQUAL_WEIGHT [a, b, ...] EVERY <tf>`: a composite of two or more declared " +
                "streams; `BUY <alias>` fans out one order per constituent.",
            "SERIES" to
                "`<alias> = SERIES ACCOUNT.EQUITY EVERY <tf>`: a read-only series sampled from account equity " +
                "(timeframe at least 1m).",
            "EQUAL_WEIGHT" to "Basket weighting: every constituent contributes equally to the composite return.",
            "SCHEDULE" to
                "Block of clock triggers: `AT HH:MM[:SS] <tz> THEN <action>`, `EVERY HOUR AT :MM THEN ...`, " +
                "`EVERY DAY|WEEKDAY AT HH:MM <tz> THEN ...`.",
            "SEQUENCE" to
                "`SEQUENCE <name> ON <alias> { STAGE <s> [WITHIN <d>]: <cond> ... }` (2-8 stages); read " +
                "`SEQUENCE.<name>.stage|complete` and `SEQUENCE.<name>.<stage>.price|time`.",
            "LET" to "`LET name = <expr>[, name2 = <expr>]`: a named expression inlined wherever it is referenced.",
            "PARAM" to
                "`PARAM name = <literal>`: a tunable number, boolean or string, overridable per run " +
                "(`--param name=value`).",
            "RULES" to "Last block of a strategy: `WHEN ... THEN ...` rules and `FOR EACH` templates.",
            "WHEN" to
                "Condition of a rule: `WHEN <cond> THEN <action>[; <action>]`. Also a `CASE WHEN` branch and " +
                "`STATE <name> WHEN <cond>` in a portfolio.",
            "THEN" to
                "Separates a WHEN condition or SCHEDULE trigger from its action; also the value of a CASE branch.",
            "FOR" to "`FOR EACH x IN [a, b] DO WHEN ... THEN ...`: expands to one rule per listed alias.",
            "EACH" to "Second word of `FOR EACH x IN [...] DO ...`.",
            "IN" to "`FOR EACH x IN [a, b]` alias list, or the membership test `<expr> IN [v1, v2, ...]`.",
            "DO" to "Closes the alias list of a `FOR EACH` template before its WHEN rule.",
            "BUY" to
                "`BUY <alias> [SIZING ...] [ORDER_TYPE = ...] [TIF ...] [BRACKET {...}] [OCO {...}] [STACK ...] " +
                "[STACK_AT ...] [TIMES n] [EXIT AFTER d] [ON_FILL {...}] [ON_STOP|ON_TP|ON_CLOSE {...}]`: go long.",
            "SELL" to "`SELL <alias> ...`: go short; takes the same option clauses as BUY.",
            "CLOSE" to
                "Action `CLOSE <alias>`: close the position on a stream. In expressions `close` is a candle field.",
            "CLOSE_ALL" to "Action: close every open position (same as FLATTEN).",
            "FLATTEN" to "Action: close every open position (same as CLOSE_ALL).",
            "RESIZE" to "`RESIZE <alias> TO <sizing> [MIN_STEP <expr>]`: bring the position to a target size.",
            "CANCEL" to "`CANCEL <alias>`: cancel the pending orders on a stream.",
            "CANCEL_ALL" to "Action: cancel every pending order.",
            "LOG" to
                "Action `LOG [WARN|ERROR|DEBUG] \"text {field}\" field = <expr> ...`. In expressions `log(x)` is " +
                "the natural logarithm.",
            "WARN" to "`LOG WARN \"...\"`: log at warning level.",
            "ERROR" to "`LOG ERROR \"...\"`: log at error level.",
            "DEBUG" to "`LOG DEBUG \"...\"`: log at debug level.",
            "STACK" to
                "`STACK <n> SPACING <dist> [ABOVE|BELOW] [WITHIN <d>]` or `STACK [<sizing> [MARKET|LIMIT AT ..|" +
                "STOP AT ..] [AT <price>], ...] [WITHIN <d>]`: add layers after the first fill.",
            "STAGE" to "`STAGE <name> [WITHIN <duration>]: <cond>` inside a SEQUENCE block.",
            "SPACING" to "`STACK <n> SPACING <dist>`: the price distance between stack layers.",
            "TIMES" to "`TIMES <expr>`: repeat the entry that many times in one evaluation.",
            "WITHIN" to
                "`WITHIN <duration>` time window on SYNCHRONIZE, STAGE, STACK, STACK_AT and " +
                "`CONFIRM RETEST_HOLD`.",
            "AFTER" to
                "`EXIT AFTER <duration>`, `TRAILING <dist> AFTER MFE >= <t>`, `STEP TO ... AFTER MFE >= <t>`.",
            "PORTFOLIO" to
                "File header `PORTFOLIO <name> VERSION <n> [CAPITAL <amount>]`; then SYMBOLS, IMPORT, REGIMES, " +
                "ALLOCATE, RULES.",
            "IMPORT" to "`IMPORT \"<path>\" AS <alias> [HOLD]`: a child strategy of a portfolio.",
            "AS" to "`IMPORT ... AS <alias>`; `LATCH ... ARM <d> AS <name>` names a latch.",
            "RUN" to
                "Portfolio rule `RUN <alias> [WEIGHT <n>] [OVERRIDE {...}]` or `WHEN <cond> RUN <alias> ...`: " +
                "activates a child.",
            "HOLD" to "`IMPORT ... AS <alias> HOLD`: keep the child managing its positions after its gate closes.",
            "CAPITAL" to "`CAPITAL <amount>` after the PORTFOLIO header: the book capital `RISK ... OF BOOK` sizes on.",
            "WEIGHT" to "`RUN <alias> WEIGHT <n>`: the child's weight.",
            "OVERRIDE" to "`RUN <alias> OVERRIDE { param = <literal>, ... }`: overrides the child's PARAMs.",
            "NAME" to "`REGIMES NAME <block>`: names a regime block.",
            "REGIMES" to "`REGIMES NAME <block> STATE <s> WHEN <cond> ... STATE <d> DEFAULT`: portfolio regimes.",
            "STATE" to "`STATE <name> WHEN <cond>` or `STATE <name> DEFAULT` inside REGIMES.",
            "DEFAULT" to "`STATE <name> DEFAULT`: the regime that holds when no other state matches.",
            "ALLOCATE" to
                "`ALLOCATE METHOD regime_weighted [REBALANCE EVERY <d>] <regime> -> <alias> <w>, CASH <w> ...`.",
            "METHOD" to "`ALLOCATE METHOD regime_weighted`: the allocation method (only regime_weighted exists).",
            "REBALANCE" to "`REBALANCE EVERY <duration>` inside ALLOCATE.",
            "CASH" to "`CASH <weight>` in an ALLOCATE entry: the unallocated share.",
            "HOUR" to "`SCHEDULE EVERY HOUR AT :MM THEN <action>`.",
            "WEEKDAY" to "`SCHEDULE EVERY WEEKDAY AT HH:MM <tz> THEN ...`; `NOW.weekday` is the current weekday.",
            "UTC" to "Timezone tag after a SCHEDULE time: `AT 09:30 UTC`.",
            "NY" to "Timezone tag after a SCHEDULE time: New York.",
            "LONDON" to "Timezone tag after a SCHEDULE time: London.",
            "TOKYO" to "Timezone tag after a SCHEDULE time: Tokyo.",
            "SYDNEY" to "Timezone tag after a SCHEDULE time: Sydney.",
            "CHICAGO" to "Timezone tag after a SCHEDULE time: Chicago.",
            "BROKER" to "Timezone tag after a SCHEDULE time: the broker's server time.",
            "ORDER_TYPE" to
                "`ORDER_TYPE = MARKET|LIMIT AT <p>|STOP AT <p> [LIMIT AT <p>]|TRAILING ...` on an action or in " +
                "DEFAULTS.",
            "STOP_LOSS" to "`STOP_LOSS = <price form>` in DEFAULTS; also accepted for `STOP LOSS` inside BRACKET.",
            "TAKE_PROFIT" to
                "`TAKE_PROFIT = <price form>` in DEFAULTS; also accepted for `TAKE PROFIT` inside BRACKET.",
            "ON_FILL" to
                "`ON_FILL { BUY|SELL ... [; ...] }`: child orders placed when the parent fills; `entry` is the " +
                "parent fill price.",
            "ON_STOP" to "`ON_STOP { <action> [; ...] }`: runs when the stop loss fills; `EXIT.*` is readable inside.",
            "ON_TP" to "`ON_TP { <action> [; ...] }`: runs when the take profit fills; `EXIT.*` is readable inside.",
            "ON_CLOSE" to
                "`ON_CLOSE { <action> [; ...] }`: runs when the position closes; `EXIT.*` is readable inside.",
            "WARMUP" to "`<alias> = ... EVERY <tf> WARMUP <n> BARS`: bars to load before the first evaluation.",
            "BARS" to "Unit of `WARMUP <n> BARS`.",
        )
}
