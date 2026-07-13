package com.qkt.trade

import java.math.BigDecimal

/**
 * Renders a [BotIntent] into a complete canonical `.qkt` strategy source. The output is
 * deterministic (same intent, same text) and parses with the standard [com.qkt.dsl.parse.Dsl]
 * parser — the DSL action grammar references instruments by stream alias, so the canonical
 * artifact carries a one-stream SYMBOLS block and a single `WHEN true` rule.
 *
 * e.g. buy 0.5 EXNESS:XAUUSD sl by:30 tp rr:2 renders
 * `THEN BUY x SIZING 0.5 BRACKET { STOP LOSS BY 30, TAKE PROFIT RR 2 }`.
 */
fun renderBotStrategy(intent: BotIntent): String {
    val clauses = mutableListOf(intent.side.name, "x", "SIZING", sizingClause(intent))
    orderTypeClause(intent)?.let { clauses.add(it) }
    tifClause(intent)?.let { clauses.add(it) }
    bracketClause(intent)?.let { clauses.add(it) }
    return """
        |STRATEGY bot VERSION 1
        |
        |SYMBOLS
        |    x = ${intent.qktSymbol} EVERY 1m
        |
        |RULES
        |    WHEN true
        |    THEN ${clauses.joinToString(" ")}
        |
        """.trimMargin()
}

private fun sizingClause(intent: BotIntent): String = intent.sizingDsl ?: num(intent.lots!!)

private fun orderTypeClause(intent: BotIntent): String? =
    when {
        intent.stopPrice != null && intent.stopLimitPrice != null ->
            "ORDER_TYPE = STOP AT ${num(intent.stopPrice)} LIMIT AT ${num(intent.stopLimitPrice)}"
        intent.stopPrice != null -> "ORDER_TYPE = STOP AT ${num(intent.stopPrice)}"
        intent.limitPrice != null -> "ORDER_TYPE = LIMIT AT ${num(intent.limitPrice)}"
        else -> null
    }

private fun tifClause(intent: BotIntent): String? =
    when (intent.tif) {
        BotTif.GTC -> null
        BotTif.DAY -> "TIF DAY"
        BotTif.GTD -> "TIF GTD ${intent.expiresAtMs}"
    }

private fun bracketClause(intent: BotIntent): String? {
    if (intent.sl == null && intent.tp == null) return null
    val children = mutableListOf<String>()
    intent.sl?.let { children.add("STOP LOSS ${exit(it)}") }
    intent.tp?.let { children.add("TAKE PROFIT ${exit(it)}") }
    return "BRACKET { ${children.joinToString(", ")} }"
}

private fun exit(spec: ExitSpec): String =
    when (spec) {
        is ExitSpec.At -> "AT ${num(spec.price)}"
        is ExitSpec.By -> "BY ${num(spec.distance)}"
        is ExitSpec.Pct -> "PCT ${num(spec.percent)}"
        is ExitSpec.Rr -> "RR ${num(spec.multiplier)}"
    }

private fun num(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()
