package com.qkt.connector.bybit.linear

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.connector.bybit.BybitExecutionKind
import com.qkt.connector.bybit.BybitSymbol
import com.qkt.connector.bybit.BybitTransport
import com.qkt.connector.bybit.requireBybitOk
import com.qkt.events.FUNDING_REPLAY_MS
import com.qkt.events.FundingCharged
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * The Bybit linear account's perpetual funding. Bybit settles each funding as an execution of
 * `execType` `Funding` (https://bybit-exchange.github.io/docs/v5/order/execution), heard on the private
 * `execution` topic ([take]) and read back from `/v5/execution/list` ([poll]); each becomes one
 * [FundingCharged] (see [charged]) the first time its `execId` is seen in [seenExecIds], and the session's
 * funding booking drops a record it already booked before a restart. The first [poll] reaches back
 * [FUNDING_REPLAY_MS] (Bybit's widest window, 7 days), so funding settled while qkt was away is booked;
 * later polls read from the last one, at most every [pollEveryMs].
 */
class BybitLinearFunding(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val seenExecIds: MutableSet<String>,
    private val pollEveryMs: Long = 5 * 60_000L,
) {
    private val log = LoggerFactory.getLogger(BybitLinearFunding::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val polledToMs = AtomicLong(clock.now() - FUNDING_REPLAY_MS)

    @Volatile private var polledOnce = false

    /** Publishes the `Funding` [execution] as a [FundingCharged], unless its `execId` was seen. */
    fun take(execution: JsonObject) {
        val charged = charged(execution)
        if (seenExecIds.add(charged.fundingId)) bus.publish(charged)
    }

    /**
     * Reads the account's `Funding` executions since the last poll (with an overlap, for a record the
     * venue lists late) and [take]s each. A failed read is logged and retried from the same point next time.
     */
    fun poll() {
        val toMs = clock.now()
        if (polledOnce && toMs - polledToMs.get() < pollEveryMs) return
        val fromMs = maxOf(polledToMs.get() - OVERLAP_MS, toMs - FUNDING_REPLAY_MS)
        runCatching { read(fromMs, toMs) }
            .onSuccess {
                polledToMs.set(toMs)
                polledOnce = true
            }.onFailure { e -> log.warn("Bybit linear funding read failed; retried next reconcile: {}", e.message) }
    }

    private fun read(
        fromMs: Long,
        toMs: Long,
    ) {
        var cursor = ""
        repeat(MAX_PAGES) {
            val params =
                buildMap {
                    put("category", "linear")
                    put("execType", "Funding")
                    put("startTime", fromMs.toString())
                    put("endTime", toMs.toString())
                    put("limit", "100")
                    if (cursor.isNotEmpty()) put("cursor", cursor)
                }
            val result =
                requireBybitOk(
                    transport.getSigned("/v5/execution/list", params),
                    "funding read",
                    json,
                )["result"]?.jsonObject
            val list = result?.get("list")?.jsonArray ?: error("funding read response omitted result.list")
            list
                .map { it.jsonObject }
                .filter {
                    BybitExecutionKind.of(
                        it,
                    ) == BybitExecutionKind.FUNDING
                }.forEach(::take)
            cursor = result["nextPageCursor"]?.jsonPrimitive?.content.orEmpty()
            if (cursor.isEmpty() || list.isEmpty()) return
        }
        log.warn("Bybit linear funding read stopped after {} pages from {}", MAX_PAGES, fromMs)
    }

    /** How a `Funding` execution reads. */
    companion object {
        private const val OVERLAP_MS = 10 * 60_000L
        private const val MAX_PAGES = 50

        /**
         * The [FundingCharged] a `Funding` [execution] means. Its `execFee` is the amount, positive when the
         * account paid: Bybit documents the transaction log's `funding` as positive when received and
         * "opposite to the execFee from Get Trade History"
         * (https://bybit-exchange.github.io/docs/v5/account/transaction-log). `execQty` is the unsigned
         * position funded and `side` its direction (`Sell` a short), so the basis is negative for a short.
         * The id is `execId`, the time `execTime`, the currency `feeCurrency` (USDT when blank).
         */
        fun charged(execution: JsonObject): FundingCharged {
            fun field(name: String): String =
                execution[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: error("Bybit funding execution missing $name: $execution")
            val size = BigDecimal(field("execQty"))
            return FundingCharged(
                fundingId = field("execId"),
                symbol = BybitSymbol.toQkt(category = "linear", bare = field("symbol")),
                amount = BigDecimal(field("execFee")),
                currency = execution["feeCurrency"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "USDT",
                basis = if (field("side") == "Sell") size.negate() else size,
                fundedAtMs = field("execTime").toLong(),
            )
        }
    }
}
