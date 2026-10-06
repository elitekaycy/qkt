package com.qkt.app

import com.qkt.dsl.compile.DslCompiledStrategy
import org.slf4j.LoggerFactory

/**
 * Hands a venue cancel or rejection to the DSL strategy that sent the order, so a rule's market exit
 * that ended without closing the position re-arms and is sent again on the rule's next bar (#1359).
 * Each such failure is logged; the [ALERT_AFTER]th in a row, and every doubling after it, raises the
 * operator alert through [alert] naming the strategy, symbol and the position still open. Retrying
 * goes on at once a bar whatever the alerts say.
 */
internal class UnfilledExitAlerts(
    private val alert: (strategyId: String, message: String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    fun onEnded(
        strategyId: String,
        strategy: DslCompiledStrategy?,
        clientOrderId: String,
        reason: String,
    ) {
        val retry = strategy?.onExitOrderUnfilled(clientOrderId) ?: return
        val held = retry.heldQuantity.stripTrailingZeros().toPlainString()
        log.warn(
            "exit order {} of {} on {} ended unfilled ({}), position {} still open: rule {} re-armed, " +
                "retry on its next bar (consecutive failures {})",
            clientOrderId,
            strategyId,
            retry.symbol,
            reason,
            held,
            retry.ruleId,
            retry.consecutiveFailures,
        )
        if (!alertsAt(retry.consecutiveFailures)) return
        val message =
            "EXIT NOT FILLED: $strategyId could not close ${retry.symbol} after ${retry.consecutiveFailures} " +
                "consecutive attempts (last: $reason); position $held still open. Rule ${retry.ruleId} keeps " +
                "retrying once a bar."
        log.error(message)
        runCatching { alert(strategyId, message) }.onFailure { log.error("exit alert failed for {}", strategyId, it) }
    }

    companion object {
        /** Consecutive failed exit fires before the first operator alert. */
        const val ALERT_AFTER = 3

        /**
         * Whether the [failures]th consecutive failed exit raises the operator alert: [ALERT_AFTER], then
         * 2x, 4x, ... so a book that stays dead keeps reminding without paging every bar.
         */
        fun alertsAt(failures: Int): Boolean {
            if (failures < ALERT_AFTER || failures % ALERT_AFTER != 0) return false
            val multiple = failures / ALERT_AFTER
            return multiple and (multiple - 1) == 0
        }
    }
}
