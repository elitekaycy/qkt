package com.qkt.dsl.compile

import java.math.BigDecimal

/**
 * A market exit rule [ruleId] sent on [symbol] ended at the venue cancelled or rejected without
 * closing the position (#1359), and the rule re-armed to send it again on its next bar.
 * [consecutiveFailures] counts the rule's fires in a row whose exit so ended; [heldQuantity] is the
 * strategy's signed position on [symbol] still open.
 */
data class ExitRetry(
    val ruleId: String,
    val symbol: String,
    val consecutiveFailures: Int,
    val heldQuantity: BigDecimal,
)
