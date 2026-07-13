package com.qkt.trade

import com.qkt.common.Clock
import com.qkt.observe.OrderJournal
import com.qkt.observe.insights.InsightsConfig
import com.qkt.observe.insights.InsightsSink
import java.nio.file.Path

/**
 * Tracking trail for one-shot bot commands: an append-only [OrderJournal] under
 * `<stateRoot>/bot/<as>/` plus, when insights egress is configured, the standard
 * envelope stream (`bot.command`, `order.submit`, `order.accepted`/`order.rejected`)
 * through an [InsightsSink]. Close before process exit so the sink flushes.
 */
class BotTrail(
    stateRoot: Path,
    insights: InsightsConfig,
    private val clock: Clock,
) : AutoCloseable {
    private val journal = OrderJournal(stateRoot.resolve("bot"), clock)
    private var seq = 0L
    private val sink: InsightsSink? =
        if (insights.enabled) {
            InsightsSink(
                url = insights.url,
                token = insights.token,
                instanceId = insights.instanceId,
                batchSize = insights.batchSize,
                flushIntervalMs = insights.flushIntervalMs,
                queueCapacity = insights.queueCapacity,
                journalDir =
                    if (insights.journalEnabled) {
                        Path.of(insights.journalDir.ifBlank { stateRoot.resolve("bot-insights-journal").toString() })
                    } else {
                        null
                    },
            )
        } else {
            null
        }

    /** Records the canonical command (source, sha256, version, argv) before submission. */
    fun recordCommand(
        asName: String,
        action: BotAction,
        argv: List<String>,
    ) {
        journal.append(
            asName,
            "bot.command",
            mapOf(
                "sha256" to action.sha256,
                "symbol" to action.qktSymbol,
                "argv" to argv.joinToString(" "),
                "source" to action.source.replace("\n", "\\n"),
            ),
        )
        sink?.offer(botCommandEnvelope(asName, action, argv, clock.now(), seq++))
    }

    /** Records the compiled order about to be submitted. */
    fun recordSubmit(
        asName: String,
        order: CompiledBotOrder,
    ) {
        journal.append(
            asName,
            "bot.submit",
            mapOf(
                "orderId" to order.request.id,
                "orderType" to order.request.javaClass.simpleName,
                "symbol" to order.request.symbol,
                "side" to order.request.side.name,
                "qty" to order.request.quantity.toPlainString(),
                "sl" to order.stopLoss?.toPlainString(),
                "tp" to order.takeProfit?.toPlainString(),
            ),
        )
        sink?.offer(botOrderSubmitEnvelope(asName, order, clock.now(), seq++))
    }

    /** Records the venue's ack or rejection. */
    fun recordResult(
        asName: String,
        order: CompiledBotOrder,
        result: BotPlaceResult,
    ) {
        journal.append(
            asName,
            if (result.ok) "bot.accepted" else "bot.rejected",
            mapOf(
                "orderId" to order.request.id,
                "ticket" to result.ticket.toString(),
                "deal" to result.deal.toString(),
                "price" to result.price.toPlainString(),
                "retcode" to result.retcode.toString(),
                "reason" to result.error,
            ),
        )
        sink?.offer(botOrderResultEnvelope(asName, order, result, clock.now(), seq++))
    }

    override fun close() {
        journal.close()
        sink?.close()
    }
}
