package com.qkt.dsl.compile

import org.slf4j.Logger

/**
 * Warns once per compiled action that an order was skipped because a price or count it needs
 * is still undefined during warm-up. Later skips from the same action stay silent, so a warming
 * indicator does not log on every bar. One instance belongs to one compiled action closure.
 */
internal class WarmupSkipLog(
    private val logger: Logger,
) {
    private var logged = false

    /** Logs `order skipped: <what> undefined during warm-up` the first time only. */
    fun skipped(
        what: String,
        ctx: EvalContext,
        stream: String,
    ) {
        if (!logged) {
            logger.warn(
                "order skipped: $what undefined during warm-up " +
                    "(strategy=${ctx.strategyContext.strategyId}, stream=$stream)",
            )
            logged = true
        }
    }
}
