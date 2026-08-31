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
    /**
     * Run-session id; when set, every envelope payload carries `run=<id>` so
     * qkt-insights can group a whole session (reads, orders, fills) as one campaign.
     * Null for plain one-shot commands — their envelopes are unchanged.
     */
    private val run: String? = null,
    /**
     * Externally-owned sink to reuse instead of building one from [insights]. A live
     * bot session shares its [com.qkt.app.LiveSession] sink here so one process never
     * runs two sinks against one insights journal. The trail never closes a shared
     * sink — the caller that built it does.
     */
    sharedSink: InsightsSink? = null,
) : AutoCloseable {
    private val journal = OrderJournal(stateRoot.resolve("bot"), clock)
    private var seq = 0L
    private val ownsSink: Boolean = sharedSink == null
    private val sink: InsightsSink? =
        sharedSink ?: if (insights.enabled) {
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
        offer(botCommandEnvelope(asName, action, argv, clock.now(), seq++))
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
        offer(botOrderSubmitEnvelope(asName, order, clock.now(), seq++))
    }

    /**
     * Records a non-entry venue action (close/modify/cancel) and its outcome as a
     * journal line plus a same-typed insights envelope, e.g. kind `bot.close`.
     */
    fun recordEvent(
        asName: String,
        kind: String,
        fields: Map<String, String?>,
    ) {
        journal.append(asName, kind, fields)
        val ts = clock.now()
        offer(
            com.qkt.observe.insights.InsightsEnvelope(
                id = "bot-$kind-$ts-$seq",
                seq = seq++,
                ts = ts,
                strategyId = asName,
                type = kind,
                payload = fields,
            ),
        )
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
        offer(botOrderResultEnvelope(asName, order, result, clock.now(), seq++))
    }

    private fun offer(envelope: com.qkt.observe.insights.InsightsEnvelope) {
        val tagged =
            if (run == null) envelope else envelope.copy(payload = envelope.payload + ("run" to run))
        sink?.offer(tagged)
    }

    override fun close() {
        journal.close()
        if (ownsSink) sink?.close()
    }
}
