package com.qkt.observe.insights

import java.math.BigDecimal

/**
 * One event on the wire to the insights collector. Mirrors the collector's contract:
 * `{v, instanceId, id, seq, ts, strategyId?, type, payload}`. The `instanceId` field is
 * stamped by [InsightsSink] at serialization time, so envelope builders don't carry it.
 *
 * Building an envelope is cheap (a small map, no I/O, no JSON) — safe on the engine
 * thread. Serialization happens on the sink's drain thread.
 */
data class InsightsEnvelope(
    val id: String,
    val seq: Long,
    val ts: Long,
    val strategyId: String?,
    val type: String,
    val payload: Map<String, Any?>,
) {
    /** Renders this envelope as a JSON object with [instanceId] stamped in. */
    fun toJson(instanceId: String): String {
        val sb = StringBuilder(160)
        sb.append("{\"v\":1,\"instanceId\":")
        appendJson(sb, instanceId)
        sb.append(",\"id\":")
        appendJson(sb, id)
        sb.append(",\"seq\":").append(seq)
        sb.append(",\"ts\":").append(ts)
        if (strategyId != null) {
            sb.append(",\"strategyId\":")
            appendJson(sb, strategyId)
        }
        sb.append(",\"type\":")
        appendJson(sb, type)
        sb.append(",\"payload\":")
        appendJson(sb, payload)
        sb.append('}')
        return sb.toString()
    }

    private companion object {
        fun appendJson(
            sb: StringBuilder,
            value: Any?,
        ) {
            when (value) {
                null -> sb.append("null")
                is String -> appendString(sb, value)
                is Boolean -> sb.append(value)
                is BigDecimal -> sb.append(value.toPlainString())
                is Number -> sb.append(value)
                is Enum<*> -> appendString(sb, value.name)
                is Map<*, *> -> {
                    sb.append('{')
                    var first = true
                    for ((k, v) in value) {
                        if (v == null) continue
                        if (!first) sb.append(',')
                        first = false
                        appendString(sb, k.toString())
                        sb.append(':')
                        appendJson(sb, v)
                    }
                    sb.append('}')
                }
                is List<*> -> {
                    sb.append('[')
                    value.forEachIndexed { i, v ->
                        if (i > 0) sb.append(',')
                        appendJson(sb, v)
                    }
                    sb.append(']')
                }
                else -> appendString(sb, value.toString())
            }
        }

        fun appendString(
            sb: StringBuilder,
            s: String,
        ) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}
