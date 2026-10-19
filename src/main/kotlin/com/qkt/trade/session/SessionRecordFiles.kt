package com.qkt.trade.session

import com.qkt.backtest.TradeRecord
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** What a session declared at its start: enough to build it again for a replay. */
data class SessionMeta(
    val runId: String,
    val mode: String,
    val identities: List<String>,
    /** The `session start` arguments, as given. */
    val argv: List<String>,
    val startedMs: Long,
)

private fun str(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Writes `session.meta.json` into [dir]. */
fun writeSessionMeta(
    dir: Path,
    meta: SessionMeta,
) {
    Files.createDirectories(dir)
    Files.writeString(
        dir.resolve("session.meta.json"),
        """{"runId":${str(meta.runId)},"mode":${str(meta.mode)},""" +
            """"identities":[${meta.identities.joinToString(",") { str(it) }}],""" +
            """"argv":[${meta.argv.joinToString(",") { str(it) }}],"startedMs":${meta.startedMs}}""" + "\n",
    )
}

/** The `session.meta.json` in [dir], or null when the directory holds no session record. */
fun readSessionMeta(dir: Path): SessionMeta? {
    val path = dir.resolve("session.meta.json")
    if (!Files.exists(path)) return null
    val o = Json.parseToJsonElement(Files.readString(path)).jsonObject

    fun text(key: String) = (o[key] as JsonPrimitive).content

    fun list(key: String) = (o[key] as JsonArray).map { (it as JsonPrimitive).content }
    return SessionMeta(text("runId"), text("mode"), list("identities"), list("argv"), text("startedMs").toLong())
}

/** Writes a backtest's executions to `fills.jsonl` in the shape a live session's record has. */
fun writeFills(
    dir: Path,
    trades: List<TradeRecord>,
) {
    val lines =
        trades.map { t ->
            """{"identity":${str(t.strategyId)},"orderId":${str(t.trade.orderId)},"symbol":${str(t.trade.symbol)},""" +
                """"side":"${t.trade.side}","qty":${t.trade.quantity.toPlainString()},""" +
                """"price":${t.trade.price.toPlainString()},"timeMs":${t.trade.timestamp}}"""
        }
    Files.write(dir.resolve("fills.jsonl"), lines)
}

/** The fills in [path] (`fills.jsonl`), oldest first; empty when absent. */
fun readFills(path: Path): List<ReplayFill> {
    if (!Files.exists(path)) return emptyList()
    return Files.readAllLines(path).filter { it.isNotBlank() }.map { line ->
        val o: JsonObject = Json.parseToJsonElement(line).jsonObject

        fun text(key: String) = (o[key] as JsonPrimitive).contentOrNull!!
        ReplayFill(
            text("identity"),
            text("symbol"),
            text("side"),
            BigDecimal(text("qty")),
            BigDecimal(text("price")),
            text("timeMs").toLong(),
        )
    }
}

/** A run's trades as fills, for a diff. */
fun fillsOf(trades: List<TradeRecord>): List<ReplayFill> =
    trades.map {
        ReplayFill(
            it.strategyId,
            it.trade.symbol,
            it.trade.side.name,
            it.trade.quantity,
            it.trade.price,
            it.trade.timestamp,
        )
    }
