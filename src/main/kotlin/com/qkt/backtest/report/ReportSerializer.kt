package com.qkt.backtest.report

import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter

internal object ReportSerializer {
    fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    fun jsonBigDecimal(v: BigDecimal): String = "\"${v.toPlainString()}\""

    fun jsonNullableBigDecimal(v: BigDecimal?): String = if (v == null) "null" else jsonBigDecimal(v)

    fun isoUtc(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
}
