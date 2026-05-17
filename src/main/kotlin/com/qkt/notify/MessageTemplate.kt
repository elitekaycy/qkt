package com.qkt.notify

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Renders a [NotificationEvent] into a plain-text Telegram message body.
 *
 * No Markdown, no HTML, no emoji. Severity prefixes `[CRITICAL]` / `[WARN]` / `[INFO]`
 * are searchable in any Telegram client. Timestamps render in UTC.
 */
object MessageTemplate {
    private val timeFmt =
        DateTimeFormatter
            .ofPattern("HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC)

    fun format(event: NotificationEvent): String {
        val t = timeFmt.format(Instant.ofEpochMilli(event.timestamp))
        val tag = "[${event.severity.name}]"
        return when (event) {
            is NotificationEvent.OrderRejected ->
                """
                $tag qkt order rejected
                strategy: ${event.strategyId}
                ${event.symbol} ${event.side.name} ${event.quantity.toPlainString()} lots
                reason: ${event.reason}
                $t
                """.trimIndent()

            is NotificationEvent.Halted -> {
                val who = event.strategyId ?: "(global)"
                """
                $tag qkt HALTED $who
                reason: ${event.reason}
                $t
                """.trimIndent()
            }

            is NotificationEvent.Resumed -> {
                val who = event.strategyId ?: "(global)"
                """
                $tag qkt resumed $who
                $t
                """.trimIndent()
            }

            is NotificationEvent.PositionReconciled ->
                """
                $tag qkt position drift ${event.strategyId}
                ${event.symbol} qty: ${(event.oldQty ?: BigDecimal.ZERO).toPlainString()} -> ${event.newQty.toPlainString()}
                reason: ${event.reason}
                $t
                """.trimIndent()

            is NotificationEvent.StrategyStarted ->
                """
                $tag qkt started ${event.strategyId}
                $t
                """.trimIndent()

            is NotificationEvent.StrategyStopped ->
                """
                $tag qkt stopped ${event.strategyId} (flatten=${event.flatten})
                $t
                """.trimIndent()

            is NotificationEvent.StrategyError ->
                """
                $tag qkt strategy error ${event.strategyId}
                message: ${event.message}
                $t
                """.trimIndent()

            is NotificationEvent.DaemonStarted ->
                """
                $tag qkt ${event.version} started
                strategies: ${event.strategies.joinToString(", ")}
                $t
                """.trimIndent()

            is NotificationEvent.DailySummary ->
                buildString {
                    append("$tag qkt daily summary ${event.asOfUtc}\n")
                    for (s in event.strategies) {
                        append("${s.strategyId}:\n")
                        append("  equity: \$${s.equity.toPlainString()} (${s.equityDeltaPct.toPlainString()}% from yesterday)\n")
                        append("  realized today: ${signed(s.realizedToday)}\n")
                        append("  unrealized: ${signed(s.unrealized)}\n")
                        append("  trades: ${s.tradesToday}\n")
                        append("  halts: ${s.haltsToday}\n")
                        append("  positions: ${s.positionsSummary}\n")
                    }
                    append(t)
                }
        }
    }

    private fun signed(v: BigDecimal): String =
        if (v.signum() >= 0) "+\$${v.toPlainString()}" else "-\$${v.abs().toPlainString()}"
}
