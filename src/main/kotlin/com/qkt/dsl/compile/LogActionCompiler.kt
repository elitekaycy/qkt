package com.qkt.dsl.compile

import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.strategy.Signal
import org.slf4j.Logger

private val LOG_PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")

/**
 * Compiles `LOG <level> "<format>" WITH <fields>`: each `{field}` placeholder renders the field's
 * value at fire time, and every field is also put on the MDC as `log.<field>` for the one line.
 */
internal class LogActionCompiler(
    private val exprCompiler: ExprCompiler,
    private val strategyLogger: Logger,
) {
    fun compile(
        log: Log,
        ruleAlias: String?,
    ): (EvalContext) -> List<Signal> {
        val placeholders = LOG_PLACEHOLDER_REGEX.findAll(log.messageFormat).map { it.groupValues[1] }.toSet()
        val unmatched = placeholders - log.fields.keys
        check(unmatched.isEmpty()) {
            "LOG placeholder(s) without matching field: ${unmatched.joinToString()}"
        }
        // With the rule's stream, a LOG field may read an aggregate (`mean(x) SINCE T-10`, `count(c, N)`),
        // which used to fail to compile with "Aggregate requires rule symbol context".
        val compiledFields = log.fields.mapValues { (_, expr) -> exprCompiler.compile(expr, ruleAlias) }
        return { ctx ->
            val resolved = compiledFields.mapValues { (_, ce) -> ce.evaluate(ctx) }
            val rendered = renderLogMessage(log.messageFormat, resolved)
            try {
                for ((k, v) in resolved) {
                    org.slf4j.MDC.put("log.$k", stringifyValue(v))
                }
                when (log.level) {
                    LogLevel.DEBUG -> strategyLogger.debug(rendered)
                    LogLevel.INFO -> strategyLogger.info(rendered)
                    LogLevel.WARN -> strategyLogger.warn(rendered)
                    LogLevel.ERROR -> strategyLogger.error(rendered)
                }
            } finally {
                for (k in resolved.keys) org.slf4j.MDC.remove("log.$k")
            }
            emptyList()
        }
    }

    private fun renderLogMessage(
        format: String,
        resolved: Map<String, Value>,
    ): String {
        var out = format
        for ((k, v) in resolved) {
            out = out.replace("{$k}", stringifyValue(v))
        }
        return out
    }

    private fun stringifyValue(v: Value): String =
        when (v) {
            is Value.Num -> v.v.toPlainString()
            is Value.Bool -> v.v.toString()
            is Value.Str -> v.v
            is Value.Undefined -> "undefined"
        }
}
