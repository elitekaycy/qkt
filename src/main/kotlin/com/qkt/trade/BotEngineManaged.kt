package com.qkt.trade

/**
 * Refuses, by name, every action shape that needs a running engine to manage: a one-shot command
 * exits after the venue's ack, so nothing would be left to trail a stop, place a child or close on time.
 */
internal fun rejectEngineManaged(bot: BotAction) {
    val opts = bot.opts
    val managed =
        when {
            opts.orderType is com.qkt.dsl.ast.TrailingBy || opts.orderType is com.qkt.dsl.ast.TrailingPct ->
                "TRAILING entries"
            opts.bracket?.stopLoss is com.qkt.dsl.ast.ChildArmedTrail -> "armed-trail stops"
            opts.stack != null -> "STACK"
            opts.stackAts.isNotEmpty() -> "STACK_AT"
            opts.oco != null -> "OCO"
            opts.onFill.isNotEmpty() || opts.exitAfter != null ->
                if (opts.exitAfter !=
                    null
                ) {
                    "EXIT AFTER"
                } else {
                    "ON FILL"
                }
            !opts.exitHooks.isEmpty() -> "exit hooks"
            else -> null
        }
    require(managed == null) {
        "$managed need a persistent engine to manage and are not supported one-shot; " +
            "run a session (qkt bot session start) or deploy a strategy"
    }
}
